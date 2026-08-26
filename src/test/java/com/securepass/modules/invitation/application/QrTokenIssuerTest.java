package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import com.securepass.shared.crypto.Ed25519Verifier;
import com.securepass.shared.crypto.MasterKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QrTokenIssuerTest {

    private static final String TEST_MASTER_KEY = "REVWX09OTFlfS0VZXzMyX0JZVEVTX1hYWFhYWFhYWFg=";
    private static final List<String> BASE_KEYS = List.of("v", "k", "j", "n", "l", "i", "t", "f", "u", "m");
    private static final List<String> RECURRING_KEYS = List.of("v", "k", "j", "n", "l", "i", "t", "f", "u", "m", "w", "df", "du");

    private SigningKeyRepository repo;
    private AesGcmCipher cipher;
    private QrTokenCodec codec;
    private QrTokenIssuer issuer;
    private ObjectMapper mapper;

    private UUID nid;
    private UUID lotId;
    private UUID invitationId;
    private UUID jti;
    private Ed25519KeyPair keyPair;

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        cipher = new AesGcmCipher(new MasterKey(TEST_MASTER_KEY));
        mapper = new ObjectMapper();
        codec = new QrTokenCodec(mapper);
        issuer = new QrTokenIssuer(repo, cipher, codec);

        nid = UUID.randomUUID();
        lotId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();
        keyPair = Ed25519KeyFactory.generate();

        SigningKey stored = new SigningKey(
                UUID.randomUUID(), nid, "abcd1234",
                keyPair.publicKey(), cipher.encrypt(keyPair.privateKey()),
                true, Instant.now(), null);
        when(repo.findActive(nid)).thenReturn(Optional.of(stored));
    }

    @Test
    void emiteTokenConPrefijoSp1YTresPartes() {
        String token = issuer.issue(cmd(InvitationType.SINGLE, 1));

        assertThat(token).startsWith("SP1.");
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void tokenSeVerificaConLaClavePublicaActiva() {
        String token = issuer.issue(cmd(InvitationType.EVENT, 50));

        ParsedQrToken parsed = codec.parse(token).orElseThrow();
        assertThat(Ed25519Verifier.verify(keyPair.publicKey(), parsed.payloadBytes(), parsed.signature())).isTrue();
        assertThat(parsed.payload().kid()).isEqualTo("abcd1234");
        assertThat(parsed.payload().invitationId()).isEqualTo(invitationId);
        assertThat(parsed.payload().type()).isEqualTo("E");
        assertThat(parsed.payload().maxUses()).isEqualTo(50);
    }

    @Test
    void payloadNoSuperaTrescientosBytes() {
        String token = issuer.issue(cmd(InvitationType.RECURRING, 200));

        ParsedQrToken parsed = codec.parse(token).orElseThrow();
        assertThat(parsed.payloadBytes().length).isLessThan(300);
    }

    @Test
    void payloadSoloContieneClavesPermitidasYSinPII() throws Exception {
        String token = issuer.issue(cmd(InvitationType.SINGLE, 1));

        ParsedQrToken parsed = codec.parse(token).orElseThrow();
        JsonNode json = mapper.readTree(parsed.payloadBytes());
        List<String> keys = java.util.stream.StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(json.fieldNames(), 0), false)
                .toList();

        assertThat(keys).containsExactlyInAnyOrderElementsOf(BASE_KEYS);
        String raw = json.toString().toLowerCase();
        assertThat(raw).doesNotContain("name");
        assertThat(raw).doesNotContain("document");
        assertThat(raw).doesNotContain("phone");
        assertThat(raw).doesNotContain("email");
        assertThat(raw).doesNotContain("guest");
    }

    @Test
    void sinClaveActivaFalla() {
        UUID otro = UUID.randomUUID();
        when(repo.findActive(otro)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> issuer.issue(new QrTokenIssuer.IssueCommand(
                otro, lotId, invitationId, jti, InvitationType.SINGLE,
                Instant.now(), Instant.now().plusSeconds(3600), 1, null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing key");
    }

    @Test
    void tamperearLaFirmaRompeLaVerificacion() {
        String token = issuer.issue(cmd(InvitationType.SINGLE, 1));
        String[] parts = token.split("\\.");
        byte[] signature = java.util.Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 0x01;
        String tampered = parts[0] + "." + parts[1] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);

        ParsedQrToken parsed = codec.parse(tampered).orElseThrow();
        assertThat(Ed25519Verifier.verify(keyPair.publicKey(), parsed.payloadBytes(), parsed.signature())).isFalse();
    }

    private QrTokenIssuer.IssueCommand cmd(InvitationType type, int maxUses) {
        Instant from = Instant.now();
        boolean r = type == InvitationType.RECURRING;
        return new QrTokenIssuer.IssueCommand(
                nid, lotId, invitationId, jti, type, from, from.plusSeconds(3600), maxUses,
                r ? List.of(1, 2, 3, 4, 5) : null,
                r ? LocalTime.of(8, 0) : null,
                r ? LocalTime.of(18, 0) : null);
    }

    @Test
    void payloadRecurringCoincideConSpec5_1() throws Exception {
        String token = issuer.issue(cmd(InvitationType.RECURRING, 200));
        ParsedQrToken parsed = codec.parse(token).orElseThrow();
        JsonNode json = mapper.readTree(parsed.payloadBytes());

        assertThat(json.get("w").toString()).isEqualTo("[1,2,3,4,5]");
        assertThat(json.get("df").asText()).isEqualTo("08:00");
        assertThat(json.get("du").asText()).isEqualTo("18:00");
    }

    @Test
    void recurringIncluyeFranjaHorariaEnPayload() throws Exception {
        String token = issuer.issue(cmd(InvitationType.RECURRING, 200));

        ParsedQrToken parsed = codec.parse(token).orElseThrow();
        JsonNode json = mapper.readTree(parsed.payloadBytes());
        List<String> keys = java.util.stream.StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(json.fieldNames(), 0), false)
                .toList();

        assertThat(keys).containsExactlyInAnyOrderElementsOf(RECURRING_KEYS);
        assertThat(json.get("w").isArray()).isTrue();
        List<Integer> w = new java.util.ArrayList<>();
        json.get("w").forEach(n -> w.add(n.asInt()));
        assertThat(w).containsExactly(1, 2, 3, 4, 5);
        assertThat(json.get("df").asText()).isEqualTo("08:00");
        assertThat(json.get("du").asText()).isEqualTo("18:00");
        assertThat(parsed.payloadBytes().length).isLessThan(300);
    }
}
