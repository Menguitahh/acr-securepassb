package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.CreateInvitationUseCase;
import com.securepass.modules.invitation.domain.InvitationType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateInvitationRequest(
        @NotNull UUID lotId,
        @NotNull InvitationType type,
        @NotBlank @Size(max = 140) String guestName,
        @NotBlank @Pattern(regexp = "^[0-9]{7,8}$", message = "guestDocument debe tener 7-8 digitos") String guestDocument,
        @Min(0) @Max(20) int companions,
        @Size(max = 280) String notes,
        @NotNull Instant validFrom,
        @NotNull Instant validUntil,
        @Min(1) int maxUses
) {

    /**
     * WBS-127: RECURRING salio del enum, pero el frontend viejo puede seguir
     * mandando "RECURRING" en el JSON. Jackson tira 400 si el valor no matchea
     * ningun caso del enum, con lo cual esta guarda es redundante para el caso
     * habitual. Se deja como defensa explicita en caso de que alguien vuelva
     * a agregar RECURRING al enum por error: el rechazo queda documentado.
     */
    @AssertTrue(message = "El tipo RECURRING fue eliminado (WBS-127). Usar SINGLE o EVENT.")
    public boolean isTypeAllowed() {
        return type == null || type == InvitationType.SINGLE || type == InvitationType.EVENT;
    }

    public CreateInvitationUseCase.Command toCommand() {
        return new CreateInvitationUseCase.Command(
                lotId, type, guestName, guestDocument, companions, notes,
                validFrom, validUntil, maxUses);
    }
}
