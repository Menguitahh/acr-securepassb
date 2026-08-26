package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AccessLogQuery {

    Page list(Filter filter);

    record Filter(
            UUID neighborhoodId,
            List<UUID> lotIdsAllowed,
            UUID lotIdFilter,
            Instant from,
            Instant to,
            Boolean allowedOnly,
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
            Instant capturedAt
    ) {}

    record Page(List<Item> items, int page, int size, long total) {}
}
