package com.securepass.modules.access.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ListAccessLogsUseCase {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(90);
    private static final int DEFAULT_SIZE = 25;

    private final AccessLogQuery logs;

    public ListAccessLogsUseCase(AccessLogQuery logs) {
        this.logs = logs;
    }

    public AccessLogQuery.Page list(
            CurrentUser actor,
            Instant from,
            Instant to,
            UUID lotIdFilter,
            Boolean allowedOnly,
            int page) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (actor.neighborhoodId() == null) {
            throw new AccessDeniedException("usuario sin barrio");
        }

        Instant now = Instant.now();
        Instant effectiveTo = to != null ? to : now;
        Instant effectiveFrom = from != null ? from : effectiveTo.minus(DEFAULT_WINDOW);

        List<UUID> lotIdsAllowed = List.of();
        if (!actor.hasRole(Role.ADMIN)) {
            // Aislamiento: residente ve solo sus lotes; guardia sin explicit
            // scope aca queda como sin acceso.
            if (actor.lots().isEmpty()) {
                throw new AccessDeniedException("sin lotes asignados");
            }
            lotIdsAllowed = actor.lots();
            if (lotIdFilter != null && !lotIdsAllowed.contains(lotIdFilter)) {
                throw new AccessDeniedException("lote fuera del alcance del usuario");
            }
        }

        return logs.list(new AccessLogQuery.Filter(
                actor.neighborhoodId(),
                lotIdsAllowed,
                lotIdFilter,
                effectiveFrom,
                effectiveTo,
                allowedOnly,
                Math.max(0, page),
                DEFAULT_SIZE));
    }
}
