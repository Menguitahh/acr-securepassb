package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface AccessLogQuery {

    Page list(Filter filter);

    /** Recorre todas las filas del filtro sin paginar, en orden cronologico (export CSV, US-32). */
    void forEach(ExportFilter filter, Consumer<Item> sink);

    /** Filas que recorreria {@link #forEach}; el XLSX la usa para rechazar antes de generar (#195). */
    long count(ExportFilter filter);

    /** Zona del barrio ({@code neighborhoods.timezone}, NOT NULL y validada al provisionar). */
    ZoneId timezone(UUID neighborhoodId);

    record Filter(
            UUID neighborhoodId,
            List<UUID> lotIdsAllowed,
            UUID lotIdFilter,
            Instant from,
            Instant to,
            Boolean allowedOnly,
            DenialReason reason,
            int page,
            int size
    ) {
        public Filter {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (page < 0) throw new IllegalArgumentException("page >= 0");
            if (size < 1 || size > 100) throw new IllegalArgumentException("size 1..100");
            lotIdsAllowed = lotIdsAllowed == null ? List.of() : List.copyOf(lotIdsAllowed);
        }
    }

    record ExportFilter(
            UUID neighborhoodId,
            UUID lotIdFilter,
            Instant from,
            Instant to,
            Boolean allowedOnly
    ) {
        public ExportFilter {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (from == null || to == null) throw new IllegalArgumentException("from y to requeridos");
        }
    }

    record Item(
            UUID id,
            UUID lotId,
            String lotCode,
            Direction direction,
            SubjectType subjectType,
            boolean allowed,
            DenialReason denialReason,
            String subjectName,
            String subjectDocument,
            int companions,
            Instant capturedAt,
            // #210: titular del pase en un denegado, por join. No es quien lo presento.
            String passHolderName
    ) {}

    record Page(List<Item> items, int page, int size, long total) {}
}
