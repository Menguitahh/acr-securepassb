package com.securepass.modules.invitation.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ListInvitationsUseCase {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final InvitationRepository invitations;

    public ListInvitationsUseCase(InvitationRepository invitations) {
        this.invitations = invitations;
    }

    public InvitationPage list(CurrentUser actor, UUID lotIdFilter, InvitationStatus statusFilter, int page) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");

        boolean admin = actor.hasRole(Role.ADMIN);
        if (!admin && lotIdFilter != null && !actor.lots().contains(lotIdFilter)) {
            throw new AccessDeniedException("lote fuera de la unidad familiar");
        }

        return invitations.list(new InvitationRepository.ListQuery(
                actor.neighborhoodId(),
                admin ? null : actor.lots(),
                lotIdFilter,
                statusFilter,
                Math.max(0, page),
                DEFAULT_PAGE_SIZE));
    }
}
