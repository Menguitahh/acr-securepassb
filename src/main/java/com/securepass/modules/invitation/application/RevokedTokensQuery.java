package com.securepass.modules.invitation.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * WBS-117 fix: consulta unificada de JTIs revocados (invitaciones + worker_tokens)
 * en una sola query SQL con UNION ALL, ORDER BY revoked_at, LIMIT.
 *
 * <p>La version anterior consultaba cada fuente con LIMIT=1000 por separado y
 * combinaba en memoria. Si una fuente tenia >1000 revocaciones desde
 * {@code since}, las de la otra fuente con {@code revoked_at} posterior
 * caian fuera del corte y no aparecian en syncs subsiguientes (el cursor
 * avanzaba al ultimo del corte, saltando las descartadas). Efecto:
 * QR revocado que seguia entrando. Con UNION y LIMIT unico, el cursor avanza
 * consistente sobre las dos fuentes ordenadas cronologicamente.
 */
public interface RevokedTokensQuery {
    List<RevokedEntry> listRevoked(UUID neighborhoodId, Instant since, int limit);
}
