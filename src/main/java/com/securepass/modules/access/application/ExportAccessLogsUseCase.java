package com.securepass.modules.access.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * US-32: export CSV de la bitacora. En dos pasos porque la respuesta es
 * streaming: {@link #start} valida, toma un slot y audita antes de
 * comprometer el status HTTP; {@link Export#stream} recorre las filas ya con
 * el body abierto y {@link Export#close} libera el slot.
 *
 * <p>#189: el cursor retiene una conexion de Hikari mientras el cliente
 * descarga (hasta ~30 s con 38 MB a 10 Mbps). El limite es global por
 * instancia porque el pool lo es: por barrio no protege nada. Mantenerlo
 * en ~20% del pool para dejar margen a garita y sync.
 */
@Service
public class ExportAccessLogsUseCase {

    // Mismo tope que /admin/metrics/traffic: el dashboard trabaja en dias y
    // semanas, y 90 dias acota el tamano del archivo.
    static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final String RULE = "export-range";
    // Orden de lo que tarda un export grande con un cliente lento.
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final AccessLogQuery logs;
    private final AccessLogExportAudit audit;
    private final Semaphore slots;

    public ExportAccessLogsUseCase(
            AccessLogQuery logs,
            AccessLogExportAudit audit,
            @Value("${securepass.export.max-concurrent:2}") int maxConcurrent) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("securepass.export.max-concurrent >= 1");
        this.logs = logs;
        this.audit = audit;
        this.slots = new Semaphore(maxConcurrent);
    }

    public Export start(
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

        // Sin espera: encolar retendria el hilo igual. El rechazo no se audita,
        // no sale ningun dato.
        if (!slots.tryAcquire()) throw new RateLimitExceededException(RETRY_AFTER);

        var filter = new AccessLogQuery.ExportFilter(actor.neighborhoodId(), lotIdFilter, from, to, allowedOnly);
        try {
            audit.record(actor.userId(), filter);
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        return new Export(filter);
    }

    /** Export en curso. Ocupa un slot hasta {@link #close}; usar con try-with-resources. */
    public final class Export implements AutoCloseable {

        private final AccessLogQuery.ExportFilter filter;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Export(AccessLogQuery.ExportFilter filter) {
            this.filter = filter;
        }

        public AccessLogQuery.ExportFilter filter() {
            return filter;
        }

        public void stream(Consumer<AccessLogQuery.Item> sink) {
            logs.forEach(filter, sink);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            slots.release();
        }
    }
}
