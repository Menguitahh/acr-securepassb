package com.securepass.modules.shift.infrastructure.rest;

import com.securepass.modules.shift.application.ShiftRepository;

import java.util.List;

// Pagina de GET /shifts, misma forma que /access-logs (#203).
public record ShiftsResponse(List<ShiftSummaryResponse> items, int page, int size, long total) {

    public static ShiftsResponse from(ShiftRepository.Page page) {
        return new ShiftsResponse(
                page.items().stream().map(ShiftSummaryResponse::from).toList(),
                page.page(), page.size(), page.total());
    }
}
