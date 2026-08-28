package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LookupActivationUseCaseTest {

    private ActivationTokenRepository tokens;
    private UserRepository users;
    private LookupActivationUseCase useCase;
    private String plaintext;
    private byte[] hash;

    @BeforeEach
    void setUp() {
        tokens = mock(ActivationTokenRepository.class);
        users = mock(UserRepository.class);
        useCase = new LookupActivationUseCase(tokens, users);
        ActivationTokenGenerator.Generated g = new ActivationTokenGenerator().generate();
        plaintext = g.plaintext();
        hash = g.hash();
    }

    @Test
    void devuelveValidCuandoElTokenEsUsable() {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                UUID.randomUUID(), userId, TokenPurpose.ACTIVATION, hash,
                now.plusSeconds(3600), null, now)));
        when(users.findById(userId)).thenReturn(Optional.of(new User(
                userId, "a@b.c", null, "hash", "Ana", null, null, UserStatus.PENDING_ACTIVATION)));

        LookupActivationUseCase.Result r = useCase.lookup(plaintext);
        assertThat(r).isInstanceOf(LookupActivationUseCase.Valid.class);
        LookupActivationUseCase.Valid v = (LookupActivationUseCase.Valid) r;
        assertThat(v.email()).isEqualTo("a@b.c");
        assertThat(v.purpose()).isEqualTo(TokenPurpose.ACTIVATION);
    }

    @Test
    void devuelveGoneCuandoElTokenNoExiste() {
        when(tokens.findByHash(hash)).thenReturn(Optional.empty());
        assertThat(useCase.lookup(plaintext)).isEqualTo(LookupActivationUseCase.Failure.GONE);
    }

    @Test
    void devuelveGoneCuandoElTokenEstaConsumido() {
        Instant now = Instant.now();
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                UUID.randomUUID(), UUID.randomUUID(), TokenPurpose.ACTIVATION, hash,
                now.plusSeconds(3600), now.minusSeconds(10), now.minusSeconds(20))));
        assertThat(useCase.lookup(plaintext)).isEqualTo(LookupActivationUseCase.Failure.GONE);
    }

    @Test
    void devuelveGoneCuandoElTokenEstaVencido() {
        Instant past = Instant.now().minusSeconds(3600);
        when(tokens.findByHash(hash)).thenReturn(Optional.of(new ActivationToken(
                UUID.randomUUID(), UUID.randomUUID(), TokenPurpose.ACTIVATION, hash,
                past.plusSeconds(60), null, past)));
        assertThat(useCase.lookup(plaintext)).isEqualTo(LookupActivationUseCase.Failure.GONE);
    }

    @Test
    void devuelveGoneAntePlaintextVacio() {
        assertThat(useCase.lookup(null)).isEqualTo(LookupActivationUseCase.Failure.GONE);
        assertThat(useCase.lookup("  ")).isEqualTo(LookupActivationUseCase.Failure.GONE);
    }
}
