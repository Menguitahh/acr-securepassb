package com.securepass.shared.security;

import com.securepass.shared.schedule.ScheduleWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerPermissionTest {

    private static ScheduleWindow anySchedule() {
        return new ScheduleWindow(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0));
    }

    @Test
    void alta_valida() {
        assertThatCode(() -> new WorkerPermission(
                UUID.randomUUID(), UUID.randomUUID(), "Jardineria", anySchedule(), true))
                .doesNotThrowAnyException();
    }

    @Test
    void userId_null_rechaza() {
        assertThatThrownBy(() -> new WorkerPermission(
                null, UUID.randomUUID(), "x", anySchedule(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lotId_null_rechaza() {
        assertThatThrownBy(() -> new WorkerPermission(
                UUID.randomUUID(), null, "x", anySchedule(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void service_vacio_rechaza() {
        assertThatThrownBy(() -> new WorkerPermission(
                UUID.randomUUID(), UUID.randomUUID(), "  ", anySchedule(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void service_null_rechaza() {
        assertThatThrownBy(() -> new WorkerPermission(
                UUID.randomUUID(), UUID.randomUUID(), null, anySchedule(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void service_excede_80_rechaza() {
        String big = "x".repeat(81);
        assertThatThrownBy(() -> new WorkerPermission(
                UUID.randomUUID(), UUID.randomUUID(), big, anySchedule(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void schedule_null_rechaza() {
        assertThatThrownBy(() -> new WorkerPermission(
                UUID.randomUUID(), UUID.randomUUID(), "x", null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
