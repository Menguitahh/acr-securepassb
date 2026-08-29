package com.securepass.shared.security;

import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FindActiveWorkerPermissionsIT {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LotPermissionEvaluator evaluator;

    UUID neighborhoodId;
    UUID userId;
    UUID lot1;
    UUID lot2;
    UUID lot3;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        userId = UUID.randomUUID();
        lot1 = UUID.randomUUID();
        lot2 = UUID.randomUUID();
        lot3 = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "Barrio T", "barrio-t-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'x', 'Worker Test', 'ACTIVE')
                """,
                userId, "w-" + UUID.randomUUID() + "@x.com");
        for (UUID lot : List.of(lot1, lot2, lot3)) {
            jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                    lot, neighborhoodId, "L-" + UUID.randomUUID().toString().substring(0, 8));
        }
    }

    private void insertWorker(UUID lot, String service, String weekdaysJson, String from, String until, boolean active) {
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'WORKER',
                        jsonb_build_object(
                            'service',   ?::text,
                            'weekdays',  ?::jsonb,
                            'dailyFrom', ?::text,
                            'dailyUntil',?::text),
                        ?)
                """, userId, lot, service, weekdaysJson, from, until, active);
    }

    @Test
    void devuelve_solo_filas_worker_activas_del_usuario() {
        insertWorker(lot1, "Jardineria", "[2,4]", "08:00", "12:00", true);
        insertWorker(lot2, "Limpieza",   "[1,3,5]", "09:30", "13:00", true);
        insertWorker(lot3, "Vieja",      "[2]", "08:00", "09:00", false); // inactiva
        // Un OWNER en otro lote no aparece
        UUID otherLot = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                otherLot, neighborhoodId, "L-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', '{}'::jsonb, TRUE)
                """, userId, otherLot);

        List<WorkerPermission> found = evaluator.findActiveWorkerPermissions(userId);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(WorkerPermission::lotId).containsExactlyInAnyOrder(lot1, lot2);
        WorkerPermission wp1 = found.stream().filter(w -> w.lotId().equals(lot1)).findFirst().orElseThrow();
        assertThat(wp1.service()).isEqualTo("Jardineria");
        assertThat(wp1.schedule().weekdays()).containsExactlyInAnyOrder(2, 4);
        assertThat(wp1.schedule().dailyFrom()).isEqualTo(LocalTime.of(8, 0));
        assertThat(wp1.schedule().dailyUntil()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void userId_null_devuelve_lista_vacia() {
        assertThat(evaluator.findActiveWorkerPermissions(null)).isEmpty();
    }

    @Test
    void usuario_sin_worker_devuelve_lista_vacia() {
        assertThat(evaluator.findActiveWorkerPermissions(userId)).isEmpty();
    }

    @Test
    void fila_con_atributos_invalidos_se_omite_sin_romper_el_resto() {
        insertWorker(lot1, "Ok", "[2]", "08:00", "12:00", true);
        // fila con dailyUntil <= dailyFrom: ScheduleWindow rechaza; el resto sobrevive
        insertWorker(lot2, "Rota", "[2]", "10:00", "10:00", true);

        List<WorkerPermission> found = evaluator.findActiveWorkerPermissions(userId);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).lotId()).isEqualTo(lot1);
    }
}
