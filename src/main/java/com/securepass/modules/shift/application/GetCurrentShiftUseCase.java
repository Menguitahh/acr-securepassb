package com.securepass.modules.shift.application;

import com.securepass.modules.shift.domain.Shift;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Service
public class GetCurrentShiftUseCase {

    private final ShiftRepository shifts;

    public Optional<Shift> get(UUID guardId) {
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");
        return shifts.findOpenByGuard(guardId);
    }
}
