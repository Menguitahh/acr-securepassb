package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import com.securepass.shared.exception.NeighborhoodNotProvisionedException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IssueWorkerTokenUseCaseTest {

    private WorkerActivePermissionQuery permissions;
    private WorkerTokenRepository tokens;
    private SigningKeyRepository keys;
    private AesGcmCipher cipher;
    private IssueWorkerTokenUseCase useCase;

    private UUID userId;
    private UUID neighborhoodId;
    private SigningKey key;

    @BeforeEach
    void setUp() {
        permissions = mock(WorkerActivePermissionQuery.class);
        tokens = mock(WorkerTokenRepository.class);
        keys = mock(SigningKeyRepository.class);
        cipher = mock(AesGcmCipher.class);
        QrTokenCodec codec = new QrTokenCodec(new ObjectMapper());
        useCase = new IssueWorkerTokenUseCase(permissions, tokens, keys, cipher, codec);

        userId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        Ed25519KeyPair kp = Ed25519KeyFactory.generate();
        key = new SigningKey(UUID.randomUUID(), neighborhoodId, "kid-1",
                kp.publicKey(), "encrypted", true, Instant.now(), null);
        when(cipher.decrypt("encrypted")).thenReturn(kp.privateKey());
    }

    @Test
    void sinPermisosDevuelve404() {
        when(permissions.findNeighborhoodOfActiveWorker(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.issueForCurrentUser(userId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(tokens, never()).insertActive(any(), any(), any());
    }

    @Test
    void sinSigningKeyActivaDevuelve503() {
        when(permissions.findNeighborhoodOfActiveWorker(userId)).thenReturn(Optional.of(neighborhoodId));
        when(keys.findActive(neighborhoodId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.issueForCurrentUser(userId))
                .isInstanceOf(NeighborhoodNotProvisionedException.class);
    }

    @Test
    void primeraLlamadaCreaJtiYRetornaSp1() {
        when(permissions.findNeighborhoodOfActiveWorker(userId)).thenReturn(Optional.of(neighborhoodId));
        when(keys.findActive(neighborhoodId)).thenReturn(Optional.of(key));
        when(tokens.findActive(userId)).thenReturn(Optional.empty());

        IssueWorkerTokenUseCase.IssuedWorkerToken issued = useCase.issueForCurrentUser(userId);

        assertThat(issued.jti()).isNotNull();
        assertThat(issued.sp1()).startsWith("SP1.");
        ArgumentCaptor<UUID> jtiCap = ArgumentCaptor.forClass(UUID.class);
        verify(tokens).insertActive(any(), any(), jtiCap.capture());
        assertThat(jtiCap.getValue()).isEqualTo(issued.jti());
    }

    @Test
    void segundaLlamadaRehidrataMismoJti() {
        UUID existingJti = UUID.randomUUID();
        when(permissions.findNeighborhoodOfActiveWorker(userId)).thenReturn(Optional.of(neighborhoodId));
        when(keys.findActive(neighborhoodId)).thenReturn(Optional.of(key));
        when(tokens.findActive(userId)).thenReturn(Optional.of(
                new WorkerTokenRepository.WorkerTokenRow(userId, neighborhoodId, existingJti, Instant.now())));

        IssueWorkerTokenUseCase.IssuedWorkerToken a = useCase.issueForCurrentUser(userId);
        IssueWorkerTokenUseCase.IssuedWorkerToken b = useCase.issueForCurrentUser(userId);

        assertThat(a.jti()).isEqualTo(existingJti);
        assertThat(b.jti()).isEqualTo(existingJti);
        // Ed25519 deterministico + payload identico -> firma byte-a-byte identica.
        assertThat(a.sp1()).isEqualTo(b.sp1());
        verify(tokens, never()).insertActive(any(), any(), any());
    }
}
