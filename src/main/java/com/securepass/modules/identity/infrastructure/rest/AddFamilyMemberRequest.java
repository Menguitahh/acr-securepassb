package com.securepass.modules.identity.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.securepass.modules.identity.application.AddFamilyMemberUseCase;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * WBS-110 / US-57: alta de familiar. Cambio breaking del contrato v1 (que
 * recibia {@code identifier} = email o telefono y buscaba el user existente):
 * ahora la identidad es por DNI (US-56) y el flujo dispara activacion.
 *
 * {@code canInvite} es obligatorio, sin default: un permiso concedido por
 * ausencia de campo es el tipo de default que despues nadie recuerda haber
 * elegido. Mobile lo manda explicito.
 */
public record AddFamilyMemberRequest(
        @NotBlank @Size(max = 140) String fullName,
        @NotBlank @Size(max = 20) String dni,
        @NotNull @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate birthDate,
        @Email @Size(max = 160) String email,
        @Size(max = 30) String relation,
        @NotNull Boolean canInvite
) {
    public AddFamilyMemberUseCase.Command toCommand() {
        return new AddFamilyMemberUseCase.Command(
                fullName, dni, birthDate, email, relation, canInvite);
    }
}
