package com.securepass.modules.shift.application;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ListShiftsUseCase {

    private static final int MAX_LIMIT = 200;

    private final ShiftRepository shifts;

    public ListShiftsUseCase(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    public List<ShiftSummary> list(UUID neighborhoodId, UUID gateId, Instant from, Instant to) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return shifts.listByFilter(neighborhoodId, gateId, from, to, MAX_LIMIT);
    }
}
