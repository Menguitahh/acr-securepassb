package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.MasterKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SigningKeyIssuerTest {

    private static final String TEST_KEY_B64 = "REVWX09OTFlfS0VZXzMyX0JZVEVTX1hYWFhYWFhYWFg=";

    private SigningKeyRepository repo;
    private AesGcmCipher cipher;
    private SigningKeyIssuer issuer;

    @BeforeEach
    void setUp() {
        repo = mock(SigningKeyRepository.class);
        cipher = new AesGcmCipher(new MasterKey(TEST_KEY_B64));
        issuer = new SigningKeyIssuer(repo, cipher);
    }

    @Test
    void emiteClaveNuevaYPersisteCifrada() {
        UUID nid = UUID.randomUUID();
        when(repo.findActive(nid)).thenReturn(Optional.empty());

        SigningKeyIssuer.IssuedKey issued = issuer.issueForNeighborhood(nid);

        ArgumentCaptor<SigningKey> saved = ArgumentCaptor.forClass(SigningKey.class);
        verify(repo).save(saved.capture());
        SigningKey stored = saved.getValue();
        assertThat(stored.neighborhoodId()).isEqualTo(nid);
        assertThat(stored.active()).isTrue();
        assertThat(stored.publicKey()).hasSize(32).isEqualTo(issued.publicKey());
        assertThat(stored.privateKeyEnc()).isNotBlank();
        assertThat(cipher.decrypt(stored.privateKeyEnc())).hasSize(32);
        verify(repo, never()).markInactive(any(), any());
    }

    @Test
    void rotacionDeactivaLaActivaPrevia() {
        UUID nid = UUID.randomUUID();
        UUID previousId = UUID.randomUUID();
        SigningKey previous = new SigningKey(previousId, nid, "prev", new byte[32], "enc", true, Instant.now(), null);
        when(repo.findActive(nid)).thenReturn(Optional.of(previous));

        issuer.issueForNeighborhood(nid);

        verify(repo).markInactive(eq(previousId), any(Instant.class));
        verify(repo).save(any(SigningKey.class));
    }

    @Test
    void kidEsHexDe8Caracteres() {
        when(repo.findActive(any())).thenReturn(Optional.empty());
        SigningKeyIssuer.IssuedKey issued = issuer.issueForNeighborhood(UUID.randomUUID());
        assertThat(issued.kid()).hasSize(8).matches("[0-9a-f]+");
    }
}
