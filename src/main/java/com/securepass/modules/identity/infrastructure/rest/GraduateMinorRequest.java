package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.GraduateMinorUseCase;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GraduateMinorRequest(
        @NotBlank @Email @Size(max = 160) String email,
        @Size(max = 30) String relation,
        @NotNull Boolean canInvite,
        @Size(max = 20) String dniOverride
) {
    public GraduateMinorUseCase.Command toCommand() {
        return new GraduateMinorUseCase.Command(email, relation, canInvite, dniOverride);
    }
}
