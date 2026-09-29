package com.securepass.modules.shift.application;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class ListShiftsUseCase {

    // Fijo, igual que /access-logs (#203).
    private static final int PAGE_SIZE = 25;

    private final ShiftRepository shifts;

    public ListShiftsUseCase(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    public ShiftRepository.Page list(UUID neighborhoodId, UUID gateId, Instant from, Instant to, int page) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return shifts.listByFilter(neighborhoodId, gateId, from, to, Math.max(0, page), PAGE_SIZE);
    }
}
