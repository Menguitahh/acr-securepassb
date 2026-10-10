package com.securepass.modules.invitation.application;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import lombok.AllArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * WBS-72 (dep. residente): el residente marca que la visita de una
 * invitacion debe retirarse. El guardia lo ve en la lista de presentes.
 *
 * Autorizacion: mismo criterio que extender (BR-17), solo el creador o el
 *  titular del lote pueden pedir salida.
 * Idempotencia: si ya estaba pedida, conserva el timestamp original.
 */
@AllArgsConstructor
@Service
public class RequestGuestExitUseCase {

    private final InvitationRepository invitations;
    private final FamilyMemberQuery family;
    private final StayRepository stays;
    private final ApplicationEventPublisher events;

    @Transactional
    public Result requestExit(UUID actorNeighborhoodId, UUID actorUserId, UUID invitationId) {
        if (actorNeighborhoodId == null) throw new IllegalArgumentException("actorNeighborhoodId requerido");
        if (actorUserId == null) throw new IllegalArgumentException("actorUserId requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");

        Invitation inv = invitations.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("invitation", invitationId));
        // Aislamiento por barrio (spec 5.5): no confirmar existencia cruzada.
        if (!inv.neighborhoodId().equals(actorNeighborhoodId)) {
            throw new ResourceNotFoundException("invitation", invitationId);
        }
        assertActorCanRequestExit(actorUserId, inv);

        Instant now = Instant.now();
        Instant persisted = stays.requestExitByInvitation(invitationId, actorUserId, now)
                .orElseThrow(() -> new StateConflictException(
                        "no hay estadia abierta para la invitacion " + invitationId));

        // WBS-72: notificar a la garita del ingreso. AFTER_COMMIT en el listener.
        events.publishEvent(new ResidentDecisionMadeEvent(
                inv.neighborhoodId(), invitationId,
                ResidentDecisionMadeEvent.Kind.EXIT_REQUESTED));

        return new Result(invitationId, persisted);
    }

    private void assertActorCanRequestExit(UUID actorUserId, Invitation inv) {
        if (actorUserId.equals(inv.createdBy())) return;
        boolean owner = family.find(actorUserId, inv.lotId())
                .map(m -> m.active() && m.isOwner())
                .orElse(false);
        if (!owner) {
            throw new AccessDeniedException("solo el creador o el titular del lote pueden pedir la salida");
        }
    }

    public record Result(UUID invitationId, Instant exitRequestedAt) {}
}
