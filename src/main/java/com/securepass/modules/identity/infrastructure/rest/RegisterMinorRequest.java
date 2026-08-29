package com.securepass.modules.identity.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.securepass.modules.identity.application.RegisterMinorUseCase;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RegisterMinorRequest(
        @NotBlank @Size(max = 140) String fullName,
        @Size(max = 20) String documentNumber,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate birthDate,
        @Size(max = 30) String relation,
        @Size(max = 280) String notes
) {
    public RegisterMinorUseCase.Command toCommand() {
        return new RegisterMinorUseCase.Command(fullName, documentNumber, birthDate, relation, notes);
    }
}
