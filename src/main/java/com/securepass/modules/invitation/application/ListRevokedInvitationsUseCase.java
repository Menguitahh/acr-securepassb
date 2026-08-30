package com.securepass.modules.invitation.application;

import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lista JTIs revocados en el barrio para el sync del scanner (/access/revoked).
 *
 * <p>Une dos fuentes: {@code invitations.status='REVOKED'} y
 * {@code worker_tokens.revoked_at IS NOT NULL} (WBS-116). Mismo shape
 * {@link RevokedEntry}, ordenado por {@code revoked_at ASC}, top {@link #MAX_LIMIT}.
 * Al scanner no le importa la fuente — un JTI revocado es un JTI revocado.
 */
@Service
public class ListRevokedInvitationsUseCase {

    private static final int MAX_LIMIT = 1000;

    private final InvitationRepository invitations;
    private final WorkerTokenRepository workerTokens;

    public ListRevokedInvitationsUseCase(
            InvitationRepository invitations,
            WorkerTokenRepository workerTokens) {
        this.invitations = invitations;
        this.workerTokens = workerTokens;
    }

    public List<RevokedEntry> list(CurrentUser actor, Instant since) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        List<RevokedEntry> combined = new ArrayList<>();
        combined.addAll(invitations.listRevoked(actor.neighborhoodId(), since, MAX_LIMIT));
        combined.addAll(workerTokens.listRevoked(actor.neighborhoodId(), since, MAX_LIMIT));
        combined.sort(Comparator.comparing(RevokedEntry::revokedAt));
        if (combined.size() > MAX_LIMIT) return combined.subList(0, MAX_LIMIT);
        return combined;
    }
}
