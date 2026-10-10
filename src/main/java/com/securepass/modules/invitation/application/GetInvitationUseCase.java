package com.securepass.modules.invitation.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class GetInvitationUseCase {

    private final InvitationRepository invitations;
    private final FamilyMemberQuery family;

    public GetInvitationUseCase(InvitationRepository invitations, FamilyMemberQuery family) {
        this.invitations = invitations;
        this.family = family;
    }

    public Invitation get(CurrentUser actor, UUID invitationId) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");

        Invitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("invitacion", invitationId));

        // Unico guardian cross-barrio para ADMIN: el @PreAuthorize deja pasar
        // hasRole('ADMIN') sin evaluar lot_permissions, y un ADMIN del barrio A
        // no tiene ninguna entrada que lo pare de leer una invitacion del B. No borrar.
        if (!invitation.neighborhoodId().equals(actor.neighborhoodId())) {
            // #123: no confirmar que existe en otro barrio.
            throw new ResourceNotFoundException("invitacion", invitationId);
        }

        if (actor.hasRole(Role.ADMIN)) return invitation;
        if (actor.userId().equals(invitation.createdBy())) return invitation;

        boolean isOwner = family.find(actor.userId(), invitation.lotId())
                .map(fm -> fm.active() && fm.isOwner())
                .orElse(false);
        if (isOwner) return invitation;

        throw new AccessDeniedException("sin permiso para ver la invitacion");
    }
}
