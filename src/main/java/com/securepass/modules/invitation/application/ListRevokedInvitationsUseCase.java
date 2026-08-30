package com.securepass.modules.invitation.application;

import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Lista JTIs revocados en el barrio para el sync del scanner (/access/revoked).
 *
 * <p>Une dos fuentes: {@code invitations.status='REVOKED'} y
 * {@code worker_tokens.revoked_at IS NOT NULL} (WBS-116). Mismo shape
 * {@link RevokedEntry}, ordenado por {@code revoked_at ASC}, top {@link #MAX_LIMIT}.
 * Al scanner no le importa la fuente — un JTI revocado es un JTI revocado.
 *
 * <p>WBS-117: la union sucede en una sola query SQL ({@link RevokedTokensQuery})
 * con UNION ALL + LIMIT unico. La version anterior (LIMIT por fuente + combine +
 * cut en memoria) tenia un bug de seguridad: si una fuente saturaba MAX_LIMIT,
 * las revocaciones mas nuevas de la otra fuente se descartaban y el cursor
 * client-side las saltaba en syncs subsiguientes.
 */
@Service
public class ListRevokedInvitationsUseCase {

    private static final int MAX_LIMIT = 1000;

    private final RevokedTokensQuery query;

    public ListRevokedInvitationsUseCase(RevokedTokensQuery query) {
        this.query = query;
    }

    public List<RevokedEntry> list(CurrentUser actor, Instant since) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return query.listRevoked(actor.neighborhoodId(), since, MAX_LIMIT);
    }
}
