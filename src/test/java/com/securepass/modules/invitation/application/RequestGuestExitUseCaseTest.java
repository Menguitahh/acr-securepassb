package com.securepass.modules.invitation.application;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

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

class RequestGuestExitUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private InvitationRepository invitations;
    private FamilyMemberQuery family;
    private StayRepository stays;
    private RequestGuestExitUseCase useCase;

    private UUID actorUserId;
    private UUID creatorUserId;
    private UUID invitationId;
    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationRepository.class);
        family = mock(FamilyMemberQuery.class);
        stays = mock(StayRepository.class);
        useCase = new RequestGuestExitUseCase(invitations, family, stays);
        actorUserId = UUID.randomUUID();
        creatorUserId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
    }

    private Invitation invitation(UUID createdBy) {
        return new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)),
                1, 0, "kid-1", UUID.randomUUID(), false, NOW);
    }

    @Test
    void creadorSolicitaSalidaYPersisteTimestamp() {
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(actorUserId)));
        when(stays.requestExitByInvitation(eq(invitationId), eq(actorUserId), any()))
                .thenAnswer(inv -> Optional.of(inv.getArgument(2, Instant.class)));

        RequestGuestExitUseCase.Result result = useCase.requestExit(neighborhoodId, actorUserId, invitationId);

        assertThat(result.invitationId()).isEqualTo(invitationId);
        assertThat(result.exitRequestedAt()).isNotNull();
    }

    // Idempotencia: si la stay ya estaba marcada, el repo devuelve el timestamp
    // original; el use case lo propaga sin fallar.
    @Test
    void idempotente() {
        Instant original = NOW.minus(Duration.ofMinutes(5));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(actorUserId)));
        when(stays.requestExitByInvitation(eq(invitationId), eq(actorUserId), any()))
                .thenReturn(Optional.of(original));

        RequestGuestExitUseCase.Result result = useCase.requestExit(neighborhoodId, actorUserId, invitationId);

        assertThat(result.exitRequestedAt()).isEqualTo(original);
    }

    // BR-17: titular del lote puede pedir la salida aunque no sea el creador.
    @Test
    void titularDelLotePuedePedirSalida() {
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(creatorUserId)));
        when(family.find(actorUserId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(actorUserId, lotId, true, true, true)));
        when(stays.requestExitByInvitation(eq(invitationId), eq(actorUserId), any()))
                .thenAnswer(inv -> Optional.of(inv.getArgument(2, Instant.class)));

        useCase.requestExit(neighborhoodId, actorUserId, invitationId);

        verify(stays).requestExitByInvitation(eq(invitationId), eq(actorUserId), any());
    }

    // BR-17: no creador ni titular -> 403.
    @Test
    void otroResidenteNoPuedePedirSalida() {
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(creatorUserId)));
        when(family.find(actorUserId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(actorUserId, lotId, false, true, true)));

        assertThatThrownBy(() -> useCase.requestExit(neighborhoodId, actorUserId, invitationId))
                .isInstanceOf(AccessDeniedException.class);
        verify(stays, never()).requestExitByInvitation(any(), any(), any());
    }

    // Aislamiento por barrio: invitacion de otro barrio devuelve 404, no 403,
    // para no confirmar existencia cruzada (spec 5.5).
    @Test
    void invitacionDeOtroBarrioEs404() {
        UUID otherNeighborhood = UUID.randomUUID();
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(actorUserId)));

        assertThatThrownBy(() -> useCase.requestExit(otherNeighborhood, actorUserId, invitationId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(stays, never()).requestExitByInvitation(any(), any(), any());
    }

    @Test
    void invitacionInexistenteEs404() {
        when(invitations.findById(invitationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.requestExit(neighborhoodId, actorUserId, invitationId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // Si no hay stay abierta (invitacion nunca usada o ya cerrada) es 409.
    @Test
    void sinStayAbiertaEs409() {
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(actorUserId)));
        when(stays.requestExitByInvitation(eq(invitationId), eq(actorUserId), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.requestExit(neighborhoodId, actorUserId, invitationId))
                .isInstanceOf(StateConflictException.class);
    }
}
