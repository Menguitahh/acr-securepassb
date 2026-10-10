package com.securepass.modules.shift.application;

import com.securepass.modules.shift.domain.Shift;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@AllArgsConstructor
@Service
public class OpenShiftUseCase {

    private final ShiftRepository shifts;

    public Shift open(UUID neighborhoodId, UUID gateId, UUID guardId, String handoverNotes) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (gateId == null) throw new IllegalArgumentException("gateId requerido");
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");

        Shift shift = new Shift(
                UUID.randomUUID(), neighborhoodId, gateId, guardId,
                Instant.now(), null, handoverNotes);
        return shifts.openIfNotOpen(shift);
    }
}
