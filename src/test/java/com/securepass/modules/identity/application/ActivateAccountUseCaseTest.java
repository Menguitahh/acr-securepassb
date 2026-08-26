package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ActivateAccountUseCaseTest {

    private ActivationTokenRepository tokens;
    private UserRepository users;
    private PasswordEncoder encoder;
    private ActivateAccountUseCase useCase;
    private String plaintext;
    private byte[] hash;
    private UUID tokenId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tokens = mock(ActivationTokenRepository.class);
        users = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        useCase = new ActivateAccountUseCase(tokens, users, encoder);
        ActivationTokenGenerator.Generated g = new ActivationTokenGenerator().generate();
        plaintext = g.plaintext();
        hash = g.hash();
        tokenId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    void activaCuandoElTokenEsUsable() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.ACTIVATION, hash, now.plusSeconds(3600), null, now)));
        when(tokens.markConsumed(tokenId)).thenReturn(true);
        when(users.activateWithPassword(eq(userId), any())).thenReturn(true);

        ActivateAccountUseCase.Result r = useCase.activate(plaintext, "supersecret");
        assertThat(r).isEqualTo(ActivateAccountUseCase.Success.INSTANCE);

        var inOrder = inOrder(tokens, users);
        inOrder.verify(tokens).markConsumed(tokenId);
        inOrder.verify(users).activateWithPassword(eq(userId), any());
    }

    @Test
    void rechazaContrasenaCorta() {
        assertThat(useCase.activate(plaintext, "abc")).isEqualTo(ActivateAccountUseCase.Failure.WEAK_PASSWORD);
        verifyNoInteractions(tokens);
        verifyNoInteractions(users);
    }

    @Test
    void devuelveGoneSiElTokenNoExiste() {
        when(tokens.findByHash(hash)).thenReturn(Optional.empty());
        assertThat(useCase.activate(plaintext, "supersecret")).isEqualTo(ActivateAccountUseCase.Failure.GONE);
    }

    @Test
    void devuelveGoneSiElTokenEsDeOtroProposito() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.PASSWORD_RESET, hash, now.plusSeconds(60), null, now)));
        assertThat(useCase.activate(plaintext, "supersecret")).isEqualTo(ActivateAccountUseCase.Failure.GONE);
        verify(tokens, never()).markConsumed(any());
    }

    @Test
    void devuelveGoneSiMarkConsumedPierdeLaCarrera() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.ACTIVATION, hash, now.plusSeconds(60), null, now)));
        when(tokens.markConsumed(tokenId)).thenReturn(false);

        assertThat(useCase.activate(plaintext, "supersecret")).isEqualTo(ActivateAccountUseCase.Failure.GONE);
        verify(users, never()).activateWithPassword(any(), any());
    }

    @Test
    void devuelveGoneSiElUsuarioYaNoAdmiteActivacion() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.ACTIVATION, hash, now.plusSeconds(60), null, now)));
        when(tokens.markConsumed(tokenId)).thenReturn(true);
        when(users.activateWithPassword(eq(userId), any())).thenReturn(false);

        assertThat(useCase.activate(plaintext, "supersecret")).isEqualTo(ActivateAccountUseCase.Failure.GONE);
    }
}
