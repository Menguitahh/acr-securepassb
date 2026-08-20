package com.securepass.modules.access.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.LotStatus;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.QrTokenPayload;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import com.securepass.shared.crypto.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValidateAccessUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");
    private static final ZoneId TZ = ZoneId.of("America/Argentina/Mendoza");

    private QrTokenCodec codec;
    private SigningKeyRepository signingKeys;
    private InvitationRepository invitations;
    private LotStatusQuery lots;
    private ValidateAccessUseCase useCase;

    private UUID neighborhoodId;
    private UUID lotId;
    private UUID invitationId;
    private UUID createdBy;
    private UUID jti;
    private Ed25519KeyPair keyPair;
    private SigningKey signingKey;

    @BeforeEach
    void setUp() {
        codec = new QrTokenCodec(new ObjectMapper());
        signingKeys = mock(SigningKeyRepository.class);
        invitations = mock(InvitationRepository.class);
        lots = mock(LotStatusQuery.class);
        useCase = new ValidateAccessUseCase(codec, signingKeys, invitations, lots);

        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        createdBy = UUID.randomUUID();
        jti = UUID.randomUUID();
        keyPair = Ed25519KeyFactory.generate();
        signingKey = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                keyPair.publicKey(), "enc-privada", true, NOW, null);
    }

    private String signedToken(QrTokenPayload payload) {
        byte[] bytes = codec.serialize(payload);
        byte[] sig = Ed25519Signer.sign(keyPair.privateKey(), bytes);
        return codec.encode(payload, sig);
    }

    private QrTokenPayload payload(Instant validFrom, Instant validUntil, int maxUses, InvitationType type) {
        boolean r = type == InvitationType.RECURRING;
        return new QrTokenPayload(1, "kid-1", jti, neighborhoodId, lotId, invitationId,
                type.code(), validFrom.getEpochSecond(), validUntil.getEpochSecond(), maxUses,
                r ? java.util.List.of(1, 2, 3, 4, 5, 6, 7) : null,
                r ? "00:00" : null, r ? "23:59" : null);
    }

    private Invitation singleInvitation(InvitationStatus status, int usedCount) {
        Instant from = NOW.minus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        return new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, status,
                "Maria Lopez", "30123456", 2, null,
                from, until, null, null, null,
                1, usedCount, "kid-1", jti, false, NOW);
    }

    private LotSnapshot activeLot() {
        return new LotSnapshot(lotId, "M4-L12", LotStatus.ACTIVE, TZ);
    }

    private void stubHappyPath(Invitation inv) {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(lots.find(lotId)).thenReturn(Optional.of(activeLot()));
    }

    @Test
    void allowedConSingleValida() {
        Instant from = NOW.minus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
        AccessDecision.Allowed allowed = (AccessDecision.Allowed) decision;
        assertThat(allowed.invitationId()).isEqualTo(invitationId);
        assertThat(allowed.jti()).isEqualTo(jti);
        assertThat(allowed.lotCode()).isEqualTo("M4-L12");
        assertThat(allowed.companions()).isEqualTo(2);
    }

    @Test
    void invalidSignatureCuandoTokenNoParsea() {
        AccessDecision decision = useCase.validate("no-es-un-token", NOW);
        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void invalidSignatureCuandoKidDesconocido() {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.empty());
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void invalidSignatureCuandoFirmaAdulterada() {
        Ed25519KeyPair otro = Ed25519KeyFactory.generate();
        SigningKey wrongKey = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                otro.publicKey(), "enc", true, NOW, null);
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(wrongKey));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void unknownCuandoInvitationNoExiste() {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.empty());
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN));
    }

    @Test
    void invalidSignatureCuandoJtiNoCoincide() {
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)),
                null, null, null, 1, 0, "kid-1", UUID.randomUUID(), false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void revokedCuandoStatusEsRevoked() {
        stubHappyPath(singleInvitation(InvitationStatus.REVOKED, 0));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED));
    }

    @Test
    void lotSuspendedCuandoLoteSuspendido() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(lots.find(lotId)).thenReturn(Optional.of(new LotSnapshot(lotId, "M4-L12", LotStatus.SUSPENDED, TZ)));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.LOT_SUSPENDED));
    }

    @Test
    void notYetValidCuandoCapturaAntesDeValidFrom() {
        Instant from = NOW.plus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                from, until, null, null, null,
                1, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.NOT_YET_VALID));
    }

    @Test
    void expiredCuandoCapturaDespuesDeValidUntil() {
        Instant from = NOW.minus(Duration.ofHours(6));
        Instant until = NOW.minus(Duration.ofHours(1));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                from, until, null, null, null,
                1, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.EXPIRED));
    }

    @Test
    void expiredCuandoStatusEsExpired() {
        Invitation inv = singleInvitation(InvitationStatus.EXPIRED, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.EXPIRED));
    }

    @Test
    void outOfScheduleCuandoRecurringFueraDeFranja() {
        Instant from = NOW.minus(Duration.ofDays(1));
        Instant until = NOW.plus(Duration.ofDays(30));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.RECURRING, InvitationStatus.ACTIVE,
                "Empleada", "30123456", 0, null,
                from, until,
                List.of(1, 2, 3, 4, 5),
                LocalTime.of(0, 0), LocalTime.of(1, 0),
                10, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 10, InvitationType.RECURRING));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.OUT_OF_SCHEDULE));
    }

    @Test
    void allowedCuandoRecurringDentroDeFranja() {
        Instant from = NOW.minus(Duration.ofDays(1));
        Instant until = NOW.plus(Duration.ofDays(30));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.RECURRING, InvitationStatus.ACTIVE,
                "Empleada", "30123456", 0, null,
                from, until,
                List.of(1, 2, 3, 4, 5, 6, 7),
                LocalTime.of(0, 0), LocalTime.of(23, 59),
                10, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 10, InvitationType.RECURRING));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    @Test
    void alreadyUsedCuandoUsedCountAlcanzaMaxUses() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.ALREADY_USED));
    }

    @Test
    void alreadyUsedCuandoStatusEsUsed() {
        Invitation inv = singleInvitation(InvitationStatus.USED, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.ALREADY_USED));
    }

    @Test
    void outAllowedAunConInvitacionYaUsada() {
        // BR-06: egresos no consumen cupo. Un pase con usedCount == maxUses
        // sigue permitiendo el egreso.
        Invitation inv = singleInvitation(InvitationStatus.USED, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW, com.securepass.modules.access.domain.Direction.OUT);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    @Test
    void outDeniedSiRevocada() {
        Invitation inv = singleInvitation(InvitationStatus.REVOKED, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW, com.securepass.modules.access.domain.Direction.OUT);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED));
    }

    // Fail-closed: si un status inconsistente esquiva todas las guardas
    // especificas, el default es Denied(UNKNOWN), nunca Allowed.
    @Test
    void defaultUnknownCuandoStatusInconsistenteEsquivaGuardas() {
        Invitation inconsistente = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.USED,
                "X", "30123456", 0, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)),
                null, null, null,
                1, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inconsistente);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(token, NOW);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN));
    }
}
