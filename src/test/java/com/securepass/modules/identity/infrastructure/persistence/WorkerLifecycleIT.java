package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.AddWorkerUseCase;
import com.securepass.modules.identity.application.ListWorkersUseCase;
import com.securepass.modules.identity.application.UpdateWorkerUseCase;
import com.securepass.modules.identity.application.WorkerListingRow;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.CurrentUser;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS-115: smoke del jardinero que trabaja en tres lotes.
 *
 * Ejercita el flujo completo contra Postgres real: alta en los tres lotes
 * (reusando la cuenta creada en el primero), listado por lote, y baja del
 * lote 2 verificando aislamiento (lotes 1 y 3 siguen activos, tanto en el
 * listado como via {@link WorkerPermissionJdbcRepository#find}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerLifecycleIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired AddWorkerUseCase addUseCase;
    @Autowired ListWorkersUseCase listUseCase;
    @Autowired UpdateWorkerUseCase updateUseCase;
    @Autowired WorkerPermissionJdbcRepository repo;

    UUID barrio;
    UUID owner;
    UUID lot1, lot2, lot3;
    CurrentUser actor;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        owner = UUID.randomUUID();
        lot1 = UUID.randomUUID();
        lot2 = UUID.randomUUID();
        lot3 = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Barrio', ?)",
                barrio, "b-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'Titular Uno', 'ACTIVE')
                """, owner, "o-" + owner + "@t.com");
        for (UUID l : List.of(lot1, lot2, lot3)) {
            jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                    l, barrio, "L-" + UUID.randomUUID().toString().substring(0, 6));
        }
        actor = new CurrentUser(owner, barrio, List.of(Role.RESIDENT_OWNER),
                List.of(lot1, lot2, lot3));
    }

    private AddWorkerUseCase.Command cmd(String service, List<Integer> weekdays,
                                         LocalTime from, LocalTime until) {
        return new AddWorkerUseCase.Command(
                "Juan Jardinero", "30111222", "juan@dev.local",
                service, new ScheduleWindow(weekdays, from, until));
    }

    @Test
    void jardineroEnTresLotesConBajaEnUnoMantieneLosOtrosDos() {
        // Lote 1: alta con user nuevo -> crea cuenta + activacion.
        AddWorkerUseCase.Result r1 = addUseCase.add(actor, lot1,
                cmd("Jardineria", List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0)),
                "Titular", "Barrio");
        assertThat(r1.outcome()).isEqualTo(AddWorkerUseCase.Outcome.CREATED_USER_AND_PERMISSION);
        UUID workerId = r1.userId();

        // Lote 2: reusa la cuenta, suma permiso con OTRO horario.
        AddWorkerUseCase.Result r2 = addUseCase.add(actor, lot2,
                cmd("Jardineria", List.of(1, 3, 5), LocalTime.of(9, 0), LocalTime.of(13, 0)),
                "Titular", "Barrio");
        assertThat(r2.outcome()).isEqualTo(AddWorkerUseCase.Outcome.ADDED_PERMISSION);
        assertThat(r2.userId()).isEqualTo(workerId);

        // Lote 3: reusa la cuenta, tercer permiso.
        AddWorkerUseCase.Result r3 = addUseCase.add(actor, lot3,
                cmd("Jardineria", List.of(6), LocalTime.of(10, 0), LocalTime.of(14, 0)),
                "Titular", "Barrio");
        assertThat(r3.outcome()).isEqualTo(AddWorkerUseCase.Outcome.ADDED_PERMISSION);
        assertThat(r3.userId()).isEqualTo(workerId);

        // Listado por lote: cada uno ve al jardinero con su franja propia.
        assertLot(lot1, "Jardineria", List.of(2, 4), "08:00", "12:00");
        assertLot(lot2, "Jardineria", List.of(1, 3, 5), "09:00", "13:00");
        assertLot(lot3, "Jardineria", List.of(6), "10:00", "14:00");

        // Baja en lote 2.
        UpdateWorkerUseCase.Result baja = updateUseCase.update(
                actor, lot2, workerId, null, null, Boolean.FALSE);
        assertThat(baja.outcome()).isEqualTo(UpdateWorkerUseCase.Outcome.DEACTIVATED);

        // Lote 2 vacio en el listado; la fila sigue en DB pero inactiva.
        assertThat(listUseCase.list(actor, lot2)).isEmpty();
        assertThat(repo.find(workerId, lot2).orElseThrow().active()).isFalse();

        // Lotes 1 y 3 intactos: el jardinero sigue activo con su franja original.
        assertLot(lot1, "Jardineria", List.of(2, 4), "08:00", "12:00");
        assertLot(lot3, "Jardineria", List.of(6), "10:00", "14:00");

        // Baja idempotente en lote 2.
        UpdateWorkerUseCase.Result bajaIdem = updateUseCase.update(
                actor, lot2, workerId, null, null, Boolean.FALSE);
        assertThat(bajaIdem.outcome()).isEqualTo(UpdateWorkerUseCase.Outcome.ALREADY_INACTIVE);

        // Reactivar sin schedule via PATCH -> StateConflictException (409).
        // La fila esta inactiva y el titular no reenvio service/schedule.
        assertThatThrownBy(() -> updateUseCase.update(
                actor, lot2, workerId, null, null, Boolean.TRUE))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("reactivar");

        // Reactivar via POST con nueva franja -> REACTIVATED_PERMISSION.
        // El titular decidio cambiar el horario en la vuelta.
        AddWorkerUseCase.Result reactivado = addUseCase.add(actor, lot2,
                cmd("Jardineria", List.of(3, 5), LocalTime.of(14, 0), LocalTime.of(18, 0)),
                "Titular", "Barrio");
        assertThat(reactivado.outcome()).isEqualTo(AddWorkerUseCase.Outcome.REACTIVATED_PERMISSION);
        assertThat(reactivado.userId()).isEqualTo(workerId);

        // La franja quedo la nueva, no la vieja: los atributos se reescriben en
        // el reactivate (jsonb_build_object, no jsonb_set parcial).
        assertLot(lot2, "Jardineria", List.of(3, 5), "14:00", "18:00");
        assertThat(repo.find(workerId, lot2).orElseThrow().active()).isTrue();

        // Los otros dos lotes siguen intactos.
        assertLot(lot1, "Jardineria", List.of(2, 4), "08:00", "12:00");
        assertLot(lot3, "Jardineria", List.of(6), "10:00", "14:00");
    }

    private void assertLot(UUID lot, String service, List<Integer> weekdays, String from, String until) {
        List<WorkerListingRow> list = listUseCase.list(actor, lot);
        assertThat(list).hasSize(1);
        WorkerListingRow row = list.get(0);
        assertThat(row.service()).isEqualTo(service);
        assertThat(row.schedule().weekdays()).containsExactlyInAnyOrderElementsOf(weekdays);
        assertThat(row.schedule().dailyFrom().toString()).isEqualTo(from);
        assertThat(row.schedule().dailyUntil().toString()).isEqualTo(until);
        assertThat(row.active()).isTrue();
    }
}
