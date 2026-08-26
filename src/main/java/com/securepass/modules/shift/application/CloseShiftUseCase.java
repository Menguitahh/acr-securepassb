package com.securepass.modules.shift.application;

import com.securepass.modules.shift.domain.Shift;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class CloseShiftUseCase {

    private final ShiftRepository shifts;

    public CloseShiftUseCase(ShiftRepository shifts) {
        this.shifts = shifts;
    }

    public Shift close(UUID guardId, String handoverNotes) {
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");

        Shift open = shifts.findOpenByGuard(guardId)
                .orElseThrow(() -> new StateConflictException("no hay turno abierto para el guardia"));
        Shift closed = open.close(Instant.now(), handoverNotes);
        shifts.close(closed);
        return closed;
    }
}
