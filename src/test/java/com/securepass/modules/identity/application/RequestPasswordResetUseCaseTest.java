package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RequestPasswordResetUseCaseTest {

    private UserRepository users;
    private IssueActivationTokenUseCase issuer;
    private RequestPasswordResetUseCase useCase;
    private UUID userId;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        issuer = mock(IssueActivationTokenUseCase.class);
        useCase = new RequestPasswordResetUseCase(users, issuer);
        userId = UUID.randomUUID();
    }

    @Test
    void emiteTokenSiElUsuarioEstaActivo() {
        when(users.findByEmail("a@b.c")).thenReturn(Optional.of(new User(
                userId, "a@b.c", null, "hash", "Ana", null, UserStatus.ACTIVE)));

        useCase.request("A@B.C");

        verify(issuer).issue(eq(userId), eq(TokenPurpose.PASSWORD_RESET), any(), any());
    }

    @Test
    void noHaceNadaSiNoExisteElEmail() {
        when(users.findByEmail("nadie@nada.com")).thenReturn(Optional.empty());

        useCase.request("nadie@nada.com");

        verifyNoInteractions(issuer);
    }

    @Test
    void noHaceNadaSiElUsuarioNoEstaActivo() {
        when(users.findByEmail("a@b.c")).thenReturn(Optional.of(new User(
                userId, "a@b.c", null, "hash", "Ana", null, UserStatus.PENDING_ACTIVATION)));

        useCase.request("a@b.c");

        verifyNoInteractions(issuer);
    }

    @Test
    void toleraInputVacio() {
        useCase.request(null);
        useCase.request("   ");
        verifyNoInteractions(users, issuer);
    }
}
