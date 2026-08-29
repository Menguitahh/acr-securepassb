package com.securepass.shared.schedule;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleWindowTest {

    private static final ZoneId MZA = ZoneId.of("America/Argentina/Mendoza");

    @Test
    void martes_a_las_10_dentro_de_martes_jueves_8_a_12() {
        ScheduleWindow w = new ScheduleWindow(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0));
        // Martes 2026-03-03 10:00 en Mendoza (UTC-3) -> 13:00Z
        Instant t = Instant.parse("2026-03-03T13:00:00Z");
        assertThat(w.contains(t, MZA)).isTrue();
    }

    @Test
    void miercoles_fuera_del_set_de_dias() {
        ScheduleWindow w = new ScheduleWindow(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0));
        Instant t = Instant.parse("2026-03-04T13:00:00Z"); // miercoles
        assertThat(w.contains(t, MZA)).isFalse();
    }

    @Test
    void justo_en_dailyFrom_incluido() {
        ScheduleWindow w = new ScheduleWindow(List.of(2), LocalTime.of(8, 0), LocalTime.of(12, 0));
        Instant t = Instant.parse("2026-03-03T11:00:00Z"); // martes 08:00 Mza
        assertThat(w.contains(t, MZA)).isTrue();
    }

    @Test
    void justo_en_dailyUntil_incluido() {
        ScheduleWindow w = new ScheduleWindow(List.of(2), LocalTime.of(8, 0), LocalTime.of(12, 0));
        Instant t = Instant.parse("2026-03-03T15:00:00Z"); // martes 12:00 Mza
        assertThat(w.contains(t, MZA)).isTrue();
    }

    @Test
    void un_segundo_despues_de_dailyUntil_fuera() {
        ScheduleWindow w = new ScheduleWindow(List.of(2), LocalTime.of(8, 0), LocalTime.of(12, 0));
        Instant t = Instant.parse("2026-03-03T15:00:01Z");
        assertThat(w.contains(t, MZA)).isFalse();
    }

    @Test
    void tz_null_fail_closed() {
        ScheduleWindow w = new ScheduleWindow(List.of(1), LocalTime.of(0, 0), LocalTime.of(23, 59));
        assertThat(w.contains(Instant.now(), null)).isFalse();
    }

    @Test
    void capturedAt_null_fail_closed() {
        ScheduleWindow w = new ScheduleWindow(List.of(1), LocalTime.of(0, 0), LocalTime.of(23, 59));
        assertThat(w.contains(null, MZA)).isFalse();
    }

    @Test
    void weekdays_vacio_rechaza() {
        assertThatThrownBy(() -> new ScheduleWindow(List.of(), LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weekdays_fuera_de_rango_rechaza() {
        assertThatThrownBy(() -> new ScheduleWindow(List.of(0), LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduleWindow(List.of(8), LocalTime.of(8, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dailyUntil_no_posterior_a_dailyFrom_rechaza() {
        assertThatThrownBy(() -> new ScheduleWindow(List.of(1), LocalTime.of(9, 0), LocalTime.of(9, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void weekdays_copia_defensiva() {
        java.util.List<Integer> mutable = new java.util.ArrayList<>(List.of(1, 2));
        ScheduleWindow w = new ScheduleWindow(mutable, LocalTime.of(8, 0), LocalTime.of(9, 0));
        mutable.clear();
        assertThat(w.weekdays()).containsExactly(1, 2);
    }
}
