package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RevokeInvitationUseCase {

    private final InvitationRepository invitations;
    private final FamilyMemberQuery family;

    public RevokeInvitationUseCase(InvitationRepository invitations, FamilyMemberQuery family) {
        this.invitations = invitations;
        this.family = family;
    }

    public Invitation revoke(CurrentUser actor, UUID invitationId) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");

        Invitation invitation = invitations.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("invitacion", invitationId));

        // Unico guardian cross-barrio para ADMIN: el @PreAuthorize deja pasar
        // hasRole('ADMIN') sin evaluar lot_permissions. No borrar.
        if (!invitation.neighborhoodId().equals(actor.neighborhoodId())) {
            // #123: no confirmar que existe en otro barrio.
            throw new ResourceNotFoundException("invitacion", invitationId);
        }

        if (!canRevoke(actor, invitation)) {
            throw new AccessDeniedException("sin permiso para revocar");
        }

        try {
            Invitation revoked = invitation.revoke();
            invitations.updateStatus(invitation.id(), InvitationStatus.REVOKED, actor.userId());
            return revoked;
        } catch (IllegalStateException ex) {
            throw new StateConflictException(ex.getMessage());
        }
    }

    private boolean canRevoke(CurrentUser actor, Invitation invitation) {
        if (actor.hasRole(Role.ADMIN)) return true;
        if (actor.userId().equals(invitation.createdBy())) return true;
        return family.find(actor.userId(), invitation.lotId())
                .map(fm -> fm.active() && fm.isOwner())
                .orElse(false);
    }
}
