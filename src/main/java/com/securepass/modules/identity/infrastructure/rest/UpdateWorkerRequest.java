package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.shared.schedule.ScheduleWindow;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;

/**
 * WBS-115 / US-63, US-64: PATCH parcial. service+schedule van juntos o
 * ninguno. active viene solo cuando es baja/reactivacion.
 *
 * Al menos uno de (service+schedule) o active debe estar. Si falta todo, el
 * request no describe ningun cambio.
 */
public record UpdateWorkerRequest(
        @Size(max = 80) String service,
        List<@NotNull @Min(1) @Max(7) Integer> weekdays,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "HH:mm") String dailyFrom,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "HH:mm") String dailyUntil,
        Boolean active
) {

    private boolean hasSchedule() {
        return weekdays != null || dailyFrom != null || dailyUntil != null;
    }

    private boolean hasService() {
        return service != null;
    }

    @AssertTrue(message = "service, weekdays, dailyFrom y dailyUntil van juntos")
    public boolean isScheduleBundled() {
        boolean any = hasService() || hasSchedule();
        if (!any) return true;
        return service != null && weekdays != null && !weekdays.isEmpty()
                && dailyFrom != null && dailyUntil != null;
    }

    @AssertTrue(message = "el request no describe ningun cambio")
    public boolean isNonEmpty() {
        return hasService() || hasSchedule() || active != null;
    }

    @AssertTrue(message = "dailyUntil debe ser posterior a dailyFrom")
    public boolean isDailyOrderValid() {
        if (dailyFrom == null || dailyUntil == null) return true;
        try {
            return LocalTime.parse(dailyUntil).isAfter(LocalTime.parse(dailyFrom));
        } catch (RuntimeException e) {
            return true;
        }
    }

    public ScheduleWindow scheduleOrNull() {
        if (!hasSchedule()) return null;
        return new ScheduleWindow(weekdays, LocalTime.parse(dailyFrom), LocalTime.parse(dailyUntil));
    }
}
