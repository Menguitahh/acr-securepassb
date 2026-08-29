package com.securepass.shared.schedule;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Franja horaria recurrente: dias de la semana (1=lunes..7=domingo) y ventana
 * horaria local [dailyFrom, dailyUntil]. Evaluacion en la zona horaria que
 * pase el caller (barrio, no dispositivo).
 *
 * Extraido de ValidateAccessUseCase.withinDailySlot (Fase 13, WBS-114) porque
 * el mismo predicado sirve para invitaciones RECURRING (§8.2) y para
 * permisos WORKER (§L). Comportamiento identico al original: dia dentro del
 * set y hora entre from (inclusive) y until (inclusive).
 */
public record ScheduleWindow(List<Integer> weekdays, LocalTime dailyFrom, LocalTime dailyUntil) {

    public ScheduleWindow {
        if (weekdays == null || weekdays.isEmpty()) {
            throw new IllegalArgumentException("weekdays requerido");
        }
        for (Integer d : weekdays) {
            if (d == null || d < 1 || d > 7) {
                throw new IllegalArgumentException("weekdays entre 1 y 7");
            }
        }
        if (dailyFrom == null || dailyUntil == null) {
            throw new IllegalArgumentException("dailyFrom y dailyUntil requeridos");
        }
        if (!dailyUntil.isAfter(dailyFrom)) {
            throw new IllegalArgumentException("dailyUntil debe ser posterior a dailyFrom");
        }
        weekdays = List.copyOf(weekdays);
    }

    public boolean contains(Instant capturedAt, ZoneId tz) {
        if (capturedAt == null || tz == null) return false;
        ZonedDateTime local = capturedAt.atZone(tz);
        int weekday = local.getDayOfWeek().getValue();
        if (!weekdays.contains(weekday)) return false;
        LocalTime time = local.toLocalTime();
        return !time.isBefore(dailyFrom) && !time.isAfter(dailyUntil);
    }
}
