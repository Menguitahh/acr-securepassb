package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.AddWorkerUseCase;
import com.securepass.shared.schedule.ScheduleWindow;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

/**
 * WBS-115 / US-62: alta de trabajador. Validacion jakarta cubre todo lo que
 * puede corromper el JSONB de {@code lot_permissions.attributes}: si algo
 * fuese mal por aca, {@link com.securepass.shared.schedule.ScheduleWindow}
 * tira {@link IllegalArgumentException} y ese cae al fallback 500 del
 * ExceptionHandler. Prevenirlo aca es la unica defensa del alta contra
 * datos que despues rompan validate.
 *
 * <p>Cross-field {@code dailyUntil > dailyFrom} via {@link AssertTrue} — no
 * hay validador jakarta stock para ese caso.
 */
public record AddWorkerRequest(
        @NotBlank @Size(max = 140) String fullName,
        @NotBlank @Size(max = 20) String dni,
        // securepassf#119: email o telefono. Sin email no hay cuenta ni QR: el
        // trabajador entra mostrando el DNI en la garita (/access/validate-document).
        @Email @Size(max = 160) String email,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 80) String service,
        @NotEmpty List<@NotNull @Min(1) @Max(7) Integer> weekdays,
        @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "HH:mm") String dailyFrom,
        @NotBlank @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "HH:mm") String dailyUntil
) {

    @AssertTrue(message = "email o phone requerido")
    public boolean isContactPresent() {
        return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
    }

    @AssertTrue(message = "dailyUntil debe ser posterior a dailyFrom")
    public boolean isDailyOrderValid() {
        if (dailyFrom == null || dailyUntil == null) return true; // otras reglas lo cubren
        try {
            return LocalTime.parse(dailyUntil).isAfter(LocalTime.parse(dailyFrom));
        } catch (RuntimeException e) {
            return true; // el @Pattern reporta el error de formato
        }
    }

    public AddWorkerUseCase.Command toCommand() {
        ScheduleWindow schedule = new ScheduleWindow(
                weekdays, LocalTime.parse(dailyFrom), LocalTime.parse(dailyUntil));
        return new AddWorkerUseCase.Command(fullName, dni, email, phone, service, schedule);
    }
}
