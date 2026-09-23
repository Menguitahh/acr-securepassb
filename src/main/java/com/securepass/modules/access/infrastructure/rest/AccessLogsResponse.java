package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securepass.modules.access.application.AccessLogQuery;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AccessLogsResponse(List<Item> items, int page, int size, long total) {

    @Schema(name = "AccessLogItem")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Item(
            UUID id,
            UUID lotId,
            String lotCode,
            String direction,
            String subjectType,
            String result,
            String denialReason,
            String subjectName,
            String subjectDocument,
            int companions,
            Instant capturedAt
    ) {}

    public static AccessLogsResponse from(AccessLogQuery.Page page) {
        List<Item> items = page.items().stream()
                .map(i -> new Item(
                        i.id(), i.lotId(), i.lotCode(),
                        i.direction().name(), i.subjectType().name(),
                        i.allowed() ? "ALLOWED" : "DENIED",
                        i.denialReason() == null ? null : i.denialReason().name(),
                        i.subjectName(), i.subjectDocument(), i.companions(),
                        i.capturedAt()))
                .toList();
        return new AccessLogsResponse(items, page.page(), page.size(), page.total());
    }
}
