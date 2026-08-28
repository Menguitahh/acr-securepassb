package com.securepass.shared.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * WBS-108 PR2 tramo 1: evaluador de permisos por lote contra
 * {@code lot_permissions}. Consulta directa a la DB por request, sin cache
 * (decision del relevamiento).
 *
 * Fail-closed: cualquier ambiguedad -> false. Argumentos null, lote/user
 * inexistente, filas inactivas, excepcion en la query, valor invalido en
 * {@code permission_type}: todo devuelve false. Ninguna ruta autoriza por
 * omision.
 *
 * Este bean queda escrito y testeado en PR2 tramo 1; el tramo 2 lo cablea
 * en los {@code @PreAuthorize} de los controllers.
 */
@Component("lotPerm")
public class LotPermissionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(LotPermissionEvaluator.class);

    private final JdbcClient jdbc;
    private final AccessGuard accessGuard;

    public LotPermissionEvaluator(JdbcClient jdbc, AccessGuard accessGuard) {
        this.jdbc = jdbc;
        this.accessGuard = accessGuard;
    }

    /**
     * Overload para SpEL: resuelve el userId del {@link AccessGuard}. Fail-closed
     * cuando no hay contexto autenticado (contexto vacio, principal anonimo o
     * de tipo inesperado). El evaluator subyacente {@link #hasAtLeast(UUID, UUID, LotPermissionType)}
     * ya devuelve false para argumentos null.
     */
    public boolean hasAtLeast(UUID lotId, LotPermissionType minimum) {
        CurrentUser user = accessGuard.tryCurrent();
        if (user == null) return false;
        return hasAtLeast(user.userId(), lotId, minimum);
    }

    /**
     * Overload para endpoints por id de invitacion (extend/revoke/exit/detail):
     * resuelve el lotId de la invitacion y delega. Un SELECT extra por request
     * en el gate (el use case vuelve a cargar la invitacion), aceptable para
     * mantener el SpEL en una linea. Fail-closed si la invitacion no existe.
     */
    public boolean hasAtLeastForInvitation(UUID invitationId, LotPermissionType minimum) {
        if (invitationId == null) return false;
        UUID lotId;
        try {
            lotId = jdbc.sql("SELECT lot_id FROM invitations WHERE id = :id")
                    .param("id", invitationId).query(UUID.class).optional().orElse(null);
        } catch (DataAccessException e) {
            log.warn("lookup invitations.lot_id fallo invitation={}: {}", invitationId, e.getMessage());
            return false;
        }
        if (lotId == null) return false;
        return hasAtLeast(lotId, minimum);
    }

    public boolean hasAtLeast(UUID userId, UUID lotId, LotPermissionType minimum) {
        if (userId == null || lotId == null || minimum == null) return false;

        List<String> raw;
        try {
            raw = jdbc.sql("""
                    SELECT permission_type
                      FROM lot_permissions
                     WHERE user_id = :uid AND lot_id = :lid AND active = TRUE
                    """)
                    .param("uid", userId)
                    .param("lid", lotId)
                    .query(String.class)
                    .list();
        } catch (DataAccessException e) {
            log.warn("lot_permissions query fallo user={} lot={}: {}",
                    userId, lotId, e.getMessage());
            return false;
        }

        if (raw.isEmpty()) return false;

        List<LotPermissionType> types = new ArrayList<>(raw.size());
        for (String s : raw) {
            try {
                types.add(LotPermissionType.valueOf(s));
            } catch (IllegalArgumentException e) {
                log.warn("permission_type desconocido={} user={} lot={} — ignorado",
                        s, userId, lotId);
            }
        }
        if (types.isEmpty()) return false;

        LotPermissionType highest = types.stream()
                .min(Comparator.comparingInt(Enum::ordinal))
                .orElseThrow();

        if (types.size() > 1) {
            log.warn("Multiples permisos activos user={} lot={} tipos={} — usando {}",
                    userId, lotId, types, highest);
        }

        return highest.atLeast(minimum);
    }
}
