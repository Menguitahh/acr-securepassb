package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.application.CreateLotUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateLotRequest(
        @NotBlank @Size(max = 30) String code,
        @Size(max = 120) String street,
        @Size(max = 20) String number
) {
    public CreateLotUseCase.Command toCommand() {
        return new CreateLotUseCase.Command(code, street, number);
    }
}
