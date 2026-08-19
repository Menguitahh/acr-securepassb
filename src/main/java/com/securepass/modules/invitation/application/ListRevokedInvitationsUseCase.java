package com.securepass.modules.invitation.application;

import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class ListRevokedInvitationsUseCase {

    private static final int MAX_LIMIT = 1000;

    private final InvitationRepository invitations;

    public ListRevokedInvitationsUseCase(InvitationRepository invitations) {
        this.invitations = invitations;
    }

    public List<RevokedEntry> list(CurrentUser actor, Instant since) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return invitations.listRevoked(actor.neighborhoodId(), since, MAX_LIMIT);
    }
}
