package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ResendActivationUseCaseTest {

    private UserRepository users;
    private IssueActivationTokenUseCase issuer;
    private ResendActivationUseCase useCase;
    private UUID userId;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        issuer = mock(IssueActivationTokenUseCase.class);
        useCase = new ResendActivationUseCase(users, issuer);
        userId = UUID.randomUUID();
    }

    @Test
    void reemiteSiElUsuarioEstaPending() {
        when(users.findById(userId)).thenReturn(Optional.of(new User(
                userId, "u@d.c", null, null, "U", null, UserStatus.PENDING_ACTIVATION)));
        useCase.resend(userId, "Admin", "Barrio");
        verify(issuer).issue(eq(userId), eq(TokenPurpose.ACTIVATION), eq("Admin"), eq("Barrio"));
    }

    @Test
    void notFoundSiElUsuarioNoExiste() {
        when(users.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.resend(userId, "A", "B"))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(issuer);
    }

    @Test
    void conflictoSiElUsuarioYaEstaActive() {
        when(users.findById(userId)).thenReturn(Optional.of(new User(
                userId, "u@d.c", null, "hash", "U", null, UserStatus.ACTIVE)));
        assertThatThrownBy(() -> useCase.resend(userId, "A", "B"))
                .isInstanceOf(StateConflictException.class);
        verifyNoInteractions(issuer);
    }

    @Test
    void conflictoSiSuspended() {
        when(users.findById(userId)).thenReturn(Optional.of(new User(
                userId, "u@d.c", null, "hash", "U", null, UserStatus.SUSPENDED)));
        assertThatThrownBy(() -> useCase.resend(userId, "A", "B"))
                .isInstanceOf(StateConflictException.class);
    }
}
