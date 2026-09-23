package com.securepass.modules.access.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * US-32: export CSV de la bitacora. En dos pasos porque la respuesta es
 * streaming: {@link #start} valida y audita antes de comprometer el status
 * HTTP, {@link #stream} recorre las filas ya con el body abierto.
 */
@Service
public class ExportAccessLogsUseCase {

    // Mismo tope que /admin/metrics/traffic: el dashboard trabaja en dias y
    // semanas, y 90 dias acota el tamano del archivo.
    static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final String RULE = "export-range";

    private final AccessLogQuery logs;
    private final AccessLogExportAudit audit;

    public ExportAccessLogsUseCase(AccessLogQuery logs, AccessLogExportAudit audit) {
        this.logs = logs;
        this.audit = audit;
    }

    public AccessLogQuery.ExportFilter start(
            CurrentUser actor, Instant from, Instant to, UUID lotIdFilter, Boolean allowedOnly) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (!actor.hasRole(Role.ADMIN)) throw new AccessDeniedException("export solo para admin");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        if (from == null || to == null) throw new BusinessRuleViolationException(RULE, "from y to son obligatorios");
        if (!to.isAfter(from)) throw new BusinessRuleViolationException(RULE, "to debe ser posterior a from");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BusinessRuleViolationException(RULE,
                    "el rango excede el maximo de " + MAX_RANGE.toDays() + " dias");
        }

        var filter = new AccessLogQuery.ExportFilter(actor.neighborhoodId(), lotIdFilter, from, to, allowedOnly);
        audit.record(actor.userId(), filter);
        return filter;
    }

    public void stream(AccessLogQuery.ExportFilter filter, Consumer<AccessLogQuery.Item> sink) {
        logs.forEach(filter, sink);
    }
}
