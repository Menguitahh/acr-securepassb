package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.application.UpdateLotUseCase;
import com.securepass.modules.neighborhood.domain.LotStatus;
import jakarta.validation.constraints.Size;

public record UpdateLotRequest(
        LotStatus status,
        @Size(max = 120) String street,
        @Size(max = 20) String number
) {
    public UpdateLotUseCase.Patch toPatch() {
        return new UpdateLotUseCase.Patch(status, street, number);
    }
}
