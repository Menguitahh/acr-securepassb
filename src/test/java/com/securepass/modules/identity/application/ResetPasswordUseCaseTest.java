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

class ResetPasswordUseCaseTest {

    private ActivationTokenRepository tokens;
    private UserRepository users;
    private PasswordEncoder encoder;
    private ResetPasswordUseCase useCase;
    private String plaintext;
    private byte[] hash;
    private UUID tokenId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        tokens = mock(ActivationTokenRepository.class);
        users = mock(UserRepository.class);
        encoder = new BCryptPasswordEncoder();
        useCase = new ResetPasswordUseCase(tokens, users, encoder);
        ActivationTokenGenerator.Generated g = new ActivationTokenGenerator().generate();
        plaintext = g.plaintext();
        hash = g.hash();
        tokenId = UUID.randomUUID();
        userId = UUID.randomUUID();
    }

    @Test
    void reseteaCuandoElTokenEsUsable() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.PASSWORD_RESET, hash, now.plusSeconds(3600), null, now)));
        when(tokens.markConsumed(tokenId)).thenReturn(true);
        when(users.updatePassword(eq(userId), any())).thenReturn(true);

        assertThat(useCase.reset(plaintext, "supersecret")).isEqualTo(ResetPasswordUseCase.Success.INSTANCE);
    }

    @Test
    void rechazaContrasenaCorta() {
        assertThat(useCase.reset(plaintext, "abc")).isEqualTo(ResetPasswordUseCase.Failure.WEAK_PASSWORD);
        verifyNoInteractions(tokens);
    }

    @Test
    void devuelveGoneSiElTokenNoEsPasswordReset() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.ACTIVATION, hash, now.plusSeconds(60), null, now)));

        assertThat(useCase.reset(plaintext, "supersecret")).isEqualTo(ResetPasswordUseCase.Failure.GONE);
        verify(tokens, never()).markConsumed(any());
    }

    @Test
    void devuelveGoneSiPierdeLaCarreraDelMarkConsumed() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.PASSWORD_RESET, hash, now.plusSeconds(60), null, now)));
        when(tokens.markConsumed(tokenId)).thenReturn(false);

        assertThat(useCase.reset(plaintext, "supersecret")).isEqualTo(ResetPasswordUseCase.Failure.GONE);
        verify(users, never()).updatePassword(any(), any());
    }

    @Test
    void devuelveGoneSiElUsuarioNoEsActivo() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                tokenId, userId, TokenPurpose.PASSWORD_RESET, hash, now.plusSeconds(60), null, now)));
        when(tokens.markConsumed(tokenId)).thenReturn(true);
        when(users.updatePassword(eq(userId), any())).thenReturn(false);

        assertThat(useCase.reset(plaintext, "supersecret")).isEqualTo(ResetPasswordUseCase.Failure.GONE);
    }
}
