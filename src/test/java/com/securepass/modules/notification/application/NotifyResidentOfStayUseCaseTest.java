package com.securepass.modules.notification.application;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.access.domain.Stay;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotifyResidentOfStayUseCaseTest {

    private static final Instant ENTER = Instant.parse("2026-08-20T15:00:00Z");

    private StayRepository stays;
    private InvitationRepository invitations;
    private PushSender push;
    private NotifyResidentOfStayUseCase useCase;

    private UUID neighborhoodId;
    private UUID stayId;
    private UUID invitationId;
    private UUID lotId;
    private UUID residentId;

    @BeforeEach
    void setUp() {
        stays = mock(StayRepository.class);
        invitations = mock(InvitationRepository.class);
        push = mock(PushSender.class);
        useCase = new NotifyResidentOfStayUseCase(stays, invitations, push);
        neighborhoodId = UUID.randomUUID();
        stayId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        residentId = UUID.randomUUID();
    }

    private Stay openStay(Instant expectedUntil) {
        return new Stay(
                stayId, neighborhoodId, invitationId, lotId,
                "Maria Lopez", "30123456", 2,
                UUID.randomUUID(), null, ENTER, null,
                expectedUntil, false, null);
    }

    private Invitation invitation() {
        return new Invitation(
                invitationId, neighborhoodId, lotId, residentId,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 2, null,
                ENTER.minus(Duration.ofHours(1)), ENTER.plus(Duration.ofHours(6)),
                null, null, null, 1, 0, "kid-1", UUID.randomUUID(), false, ENTER);
    }

    @Test
    void disparaPushYMarcaResidentNotifiedAt() {
        Instant expected = ENTER.plusSeconds(3600);
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay(expected)));
        when(stays.findResidentNotifiedAt(stayId)).thenReturn(Optional.empty());
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));

        NotifyResidentOfStayUseCase.Result result = useCase.notify(neighborhoodId, stayId);

        assertThat(result.stayId()).isEqualTo(stayId);
        ArgumentCaptor<PushSender.StayWarning> payload = ArgumentCaptor.forClass(PushSender.StayWarning.class);
        verify(push).notifyStayWarning(eq(residentId), payload.capture());
        assertThat(payload.getValue().subjectName()).isEqualTo("Maria Lopez");
        assertThat(payload.getValue().enteredAt()).isEqualTo(ENTER);
        verify(stays).markResidentNotified(eq(stayId), any(Instant.class));
    }

    // Rate limit especifico: 1 push por stay cada 10 minutos. Sin esto el
    // residente puede recibir veinte push seguidos por la misma visita.
    @Test
    void rechazaSiSeNotificoHaceMenosDe10Minutos() {
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay(ENTER.plusSeconds(3600))));
        when(stays.findResidentNotifiedAt(stayId)).thenReturn(Optional.of(Instant.now().minusSeconds(60)));

        assertThatThrownBy(() -> useCase.notify(neighborhoodId, stayId))
                .isInstanceOf(RateLimitExceededException.class);
        verify(push, never()).notifyStayWarning(any(), any());
        verify(stays, never()).markResidentNotified(any(), any());
    }

    @Test
    void permiteSiPasoElIntervalo() {
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay(ENTER.plusSeconds(3600))));
        when(stays.findResidentNotifiedAt(stayId)).thenReturn(Optional.of(Instant.now().minusSeconds(11 * 60)));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation()));

        useCase.notify(neighborhoodId, stayId);

        verify(push).notifyStayWarning(eq(residentId), any());
    }

    @Test
    void stayCerradaEs409() {
        Stay closed = openStay(ENTER.plusSeconds(3600)).close(UUID.randomUUID(), ENTER.plusSeconds(60), false, null);
        when(stays.findById(stayId)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> useCase.notify(neighborhoodId, stayId))
                .isInstanceOf(StateConflictException.class);
        verify(push, never()).notifyStayWarning(any(), any());
    }

    @Test
    void stayInexistenteEs404() {
        when(stays.findById(stayId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.notify(neighborhoodId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void stayDeOtroBarrioEs404() {
        Stay foreign = new Stay(
                stayId, UUID.randomUUID(), invitationId, lotId,
                "Maria Lopez", "30123456", 2,
                UUID.randomUUID(), null, ENTER, null,
                ENTER.plusSeconds(3600), false, null);
        when(stays.findById(stayId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> useCase.notify(neighborhoodId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(push, never()).notifyStayWarning(any(), any());
    }

    @Test
    void invitationInexistenteEs404() {
        when(stays.findById(stayId)).thenReturn(Optional.of(openStay(ENTER.plusSeconds(3600))));
        when(invitations.findById(invitationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.notify(neighborhoodId, stayId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(push, never()).notifyStayWarning(any(), any());
    }
}
