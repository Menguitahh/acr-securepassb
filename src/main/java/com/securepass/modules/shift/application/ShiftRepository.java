package com.securepass.modules.shift.application;

import com.securepass.modules.shift.domain.Shift;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShiftRepository {

    /**
     * Persiste un turno abierto. Si el guardia ya tiene uno abierto lanza
     * `StateConflictException` (BR-09: `uq_guard_open_shift`).
     */
    Shift openIfNotOpen(Shift shift);

    Optional<Shift> findOpenByGuard(UUID guardId);

    Optional<Shift> findById(UUID id);

    void close(Shift closedShift);

    List<ShiftSummary> listByFilter(UUID neighborhoodId, UUID gateId, Instant from, Instant to, int limit);
}
