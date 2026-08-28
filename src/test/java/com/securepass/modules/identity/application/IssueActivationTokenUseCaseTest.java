package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class IssueActivationTokenUseCaseTest {

    private UserRepository users;
    private ActivationTokenRepository tokens;
    private ActivationTokenGenerator generator;
    private ApplicationEventPublisher events;
    private IssueActivationTokenUseCase useCase;
    private UUID userId;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(ActivationTokenRepository.class);
        generator = new ActivationTokenGenerator();
        events = mock(ApplicationEventPublisher.class);
        useCase = new IssueActivationTokenUseCase(users, tokens, generator, events, 7, 2);
        userId = UUID.randomUUID();
        user = new User(userId, "u@dev.local", null, "hash", "Ana", null, null, UserStatus.PENDING_ACTIVATION);
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void invalidaTokensAnterioresAntesDeGuardarElNuevo() {
        useCase.issue(userId, TokenPurpose.ACTIVATION, "Admin", "Barrio Test");

        var inOrder = inOrder(tokens);
        inOrder.verify(tokens).consumeAllActive(userId, TokenPurpose.ACTIVATION);
        inOrder.verify(tokens).save(any(ActivationToken.class));
    }

    @Test
    void guardaTokenConTtl7DiasParaActivacion() {
        useCase.issue(userId, TokenPurpose.ACTIVATION, "Admin", "Barrio");

        ArgumentCaptor<ActivationToken> saved = ArgumentCaptor.forClass(ActivationToken.class);
        verify(tokens).save(saved.capture());
        ActivationToken t = saved.getValue();
        assertThat(t.userId()).isEqualTo(userId);
        assertThat(t.purpose()).isEqualTo(TokenPurpose.ACTIVATION);
        long horas = java.time.Duration.between(t.createdAt(), t.expiresAt()).toHours();
        assertThat(horas).isBetween(7L * 24 - 1, 7L * 24 + 1);
    }

    @Test
    void guardaTokenConTtl2HorasParaPasswordReset() {
        useCase.issue(userId, TokenPurpose.PASSWORD_RESET, null, null);

        ArgumentCaptor<ActivationToken> saved = ArgumentCaptor.forClass(ActivationToken.class);
        verify(tokens).save(saved.capture());
        long horas = java.time.Duration.between(saved.getValue().createdAt(), saved.getValue().expiresAt()).toHours();
        assertThat(horas).isBetween(1L, 2L);
    }

    @Test
    void publicaEventoConPlaintextParaElListener() {
        useCase.issue(userId, TokenPurpose.ACTIVATION, "Admin", "Barrio Test");

        ArgumentCaptor<ActivationTokenIssuedEvent> evt = ArgumentCaptor.forClass(ActivationTokenIssuedEvent.class);
        verify(events).publishEvent(evt.capture());
        ActivationTokenIssuedEvent e = evt.getValue();
        assertThat(e.userId()).isEqualTo(userId);
        assertThat(e.email()).isEqualTo("u@dev.local");
        assertThat(e.plaintextToken()).isNotBlank();
        assertThat(e.invitedByName()).isEqualTo("Admin");
        assertThat(e.neighborhoodName()).isEqualTo("Barrio Test");
    }

    @Test
    void rechazaSiElUsuarioNoTieneEmail() {
        User sinEmail = new User(userId, null, "+5491199999999", "hash", "Sin Email", null, null, UserStatus.PENDING_ACTIVATION);
        when(users.findById(userId)).thenReturn(Optional.of(sinEmail));

        assertThatThrownBy(() -> useCase.issue(userId, TokenPurpose.ACTIVATION, "x", "y"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokens, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void rechazaSiElUsuarioNoExiste() {
        when(users.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.issue(userId, TokenPurpose.ACTIVATION, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(tokens, never()).consumeAllActive(any(), any());
    }
}
