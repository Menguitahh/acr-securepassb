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
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * US-32: export CSV y XLSX (#195) de la bitacora. En dos pasos porque la respuesta es
 * streaming: {@link #start} valida, toma un slot y audita antes de
 * comprometer el status HTTP; {@link Export#stream} recorre las filas ya con
 * el body abierto y {@link Export#close} libera el slot.
 *
 * <p>#189: el cursor retiene una conexion de Hikari mientras el cliente
 * descarga (hasta ~30 s con 38 MB a 10 Mbps). El limite es global por
 * instancia porque el pool lo es: por barrio no protege nada. Mantenerlo
 * en ~20% del pool para dejar margen a garita y sync. CSV y XLSX comparten
 * el mismo contador: los dos retienen una conexion.
 *
 * <p>#195: el XLSX tiene tope de filas. SXSSF genera con la conexion tomada
 * (~23 us por fila) y escribe ~520 B por fila de temporal en disco; 500k
 * filas son ~11,5 s y ~260 MB. El maximo de Excel es 1.048.575 filas de datos.
 */
@Service
public class ExportAccessLogsUseCase {

    // Mismo tope que /admin/metrics/traffic: el dashboard trabaja en dias y
    // semanas, y 90 dias acota el tamano del archivo.
    static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final String RULE = "export-range";
    private static final String ROWS_RULE = "export-rows";
    // Una hoja de Excel admite 1.048.576 filas; una es el encabezado.
    static final int EXCEL_MAX_ROWS = 1_048_575;
    // Orden de lo que tarda un export grande con un cliente lento.
    private static final Duration RETRY_AFTER = Duration.ofSeconds(30);

    private final AccessLogQuery logs;
    private final AccessLogExportAudit audit;
    private final Semaphore slots;
    private final int xlsxMaxRows;

    public ExportAccessLogsUseCase(
            AccessLogQuery logs,
            AccessLogExportAudit audit,
            @Value("${securepass.export.max-concurrent:2}") int maxConcurrent,
            @Value("${securepass.export.xlsx-max-rows:500000}") int xlsxMaxRows) {
        if (maxConcurrent < 1) throw new IllegalArgumentException("securepass.export.max-concurrent >= 1");
        if (xlsxMaxRows < 1 || xlsxMaxRows > EXCEL_MAX_ROWS) {
            throw new IllegalArgumentException("securepass.export.xlsx-max-rows entre 1 y " + EXCEL_MAX_ROWS);
        }
        this.logs = logs;
        this.audit = audit;
        this.slots = new Semaphore(maxConcurrent);
        this.xlsxMaxRows = xlsxMaxRows;
    }

    public Export start(
            CurrentUser actor, Instant from, Instant to, UUID lotIdFilter, Boolean allowedOnly, ExportFormat format) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (format == null) throw new IllegalArgumentException("format requerido");
        if (!actor.hasRole(Role.ADMIN)) throw new AccessDeniedException("export solo para admin");
        if (actor.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        if (from == null || to == null) throw new BusinessRuleViolationException(RULE, "from y to son obligatorios");
        if (!to.isAfter(from)) throw new BusinessRuleViolationException(RULE, "to debe ser posterior a from");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BusinessRuleViolationException(RULE,
                    "el rango excede el maximo de " + MAX_RANGE.toDays() + " dias");
        }

        // #192: antes de tomar slot, asi un fallo aca no deja nada que liberar.
        ZoneId zone = logs.timezone(actor.neighborhoodId());
        var filter = new AccessLogQuery.ExportFilter(actor.neighborhoodId(), lotIdFilter, from, to, allowedOnly);

        // Antes del slot, como el rango: el rechazo no retiene nada ni se audita.
        if (format == ExportFormat.XLSX && logs.count(filter) > xlsxMaxRows) {
            throw new BusinessRuleViolationException(ROWS_RULE,
                    "el rango supera las " + xlsxMaxRows + " filas del XLSX; reducir el rango o usar el CSV");
        }

        // Sin espera: encolar retendria el hilo igual. El rechazo no se audita,
        // no sale ningun dato.
        if (!slots.tryAcquire()) throw new RateLimitExceededException(RETRY_AFTER);

        try {
            audit.record(actor.userId(), filter, format);
        } catch (RuntimeException e) {
            slots.release();
            throw e;
        }
        return new Export(filter, zone);
    }

    /** Export en curso. Ocupa un slot hasta {@link #close}; usar con try-with-resources. */
    public final class Export implements AutoCloseable {

        private final AccessLogQuery.ExportFilter filter;
        private final ZoneId zone;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Export(AccessLogQuery.ExportFilter filter, ZoneId zone) {
            this.filter = filter;
            this.zone = zone;
        }

        public AccessLogQuery.ExportFilter filter() {
            return filter;
        }

        /** Zona del barrio: el export sale en hora local (#192). */
        public ZoneId zone() {
            return zone;
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
