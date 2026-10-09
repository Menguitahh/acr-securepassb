package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.AddWorkerUseCase;
import com.securepass.modules.identity.application.UpdateWorkerUseCase;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.IssueWorkerTokenUseCase;
import com.securepass.modules.invitation.application.ListRevokedInvitationsUseCase;
import com.securepass.modules.invitation.application.RevokedEntry;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
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

/**
 * WBS-116: rotacion del JTI cuando el user pierde el ultimo permiso WORKER,
 * y propagacion del JTI viejo a /access/revoked.
 *
 * <p>Flow contra Postgres real:
 * <ol>
 *   <li>Titular da de alta un jardinero en 3 lotes.</li>
 *   <li>Primera emision del QR crea el JTI; segunda emision rehidrata mismo JTI (SP1 identico).</li>
 *   <li>Baja en 2 lotes -> worker_token sigue activo (aun hay 1 permiso).</li>
 *   <li>Baja del ultimo lote -> worker_token revocado; JTI en /access/revoked.</li>
 *   <li>POST reactivate en un lote con nueva franja -> GET /me/worker-qr emite JTI NUEVO.</li>
 *   <li>Baja de ese ultimo lote otra vez -> nuevo JTI tambien en /access/revoked;
 *       viejo sigue listado. Ambos revocados, ambos rechazables offline.</li>
 * </ol>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class WorkerTokenRotationIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired SigningKeyIssuer signingKeys;
    @Autowired AddWorkerUseCase addUseCase;
    @Autowired UpdateWorkerUseCase updateUseCase;
    @Autowired IssueWorkerTokenUseCase issueUseCase;
    @Autowired ListRevokedInvitationsUseCase listRevokedUseCase;

    UUID neighborhoodId;
    UUID owner;
    UUID lot1, lot2, lot3;
    String dni;
    CurrentUser ownerActor;
    CurrentUser guard;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        owner = UUID.randomUUID();
        lot1 = UUID.randomUUID();
        lot2 = UUID.randomUUID();
        lot3 = UUID.randomUUID();
        dni = String.valueOf(20_000_000 + new java.util.Random().nextInt(70_000_000));

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Barrio', ?)",
                neighborhoodId, "b-" + UUID.randomUUID());
        signingKeys.issueForNeighborhood(neighborhoodId);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'Titular', 'ACTIVE')
                """, owner, "o-" + owner + "@t.com");
        for (UUID l : List.of(lot1, lot2, lot3)) {
            jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                    l, neighborhoodId, "L-" + UUID.randomUUID().toString().substring(0, 6));
        }
        ownerActor = new CurrentUser(owner, neighborhoodId, List.of(Role.RESIDENT_OWNER),
                List.of(lot1, lot2, lot3));
        guard = new CurrentUser(UUID.randomUUID(), neighborhoodId, List.of(Role.GUARD), List.of());
    }

    private AddWorkerUseCase.Command cmd(List<Integer> weekdays, LocalTime from, LocalTime until) {
        return new AddWorkerUseCase.Command(
                "Juan Jardinero", dni, "juan-" + dni + "@dev.local", null,
                "Jardineria", new ScheduleWindow(weekdays, from, until));
    }

    @Test
    void rotacionDelJtiCuandoSePierdeElUltimoPermisoYPropagacionARevoked() {
        // Alta en 3 lotes.
        AddWorkerUseCase.Result r1 = addUseCase.add(ownerActor, lot1,
                cmd(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0)),
                "Titular", "Barrio");
        UUID workerId = r1.userId();
        addUseCase.add(ownerActor, lot2,
                cmd(List.of(1, 3, 5), LocalTime.of(9, 0), LocalTime.of(13, 0)),
                "Titular", "Barrio");
        addUseCase.add(ownerActor, lot3,
                cmd(List.of(6), LocalTime.of(10, 0), LocalTime.of(14, 0)),
                "Titular", "Barrio");

        // Primer QR: crea JTI.
        IssueWorkerTokenUseCase.IssuedWorkerToken first = issueUseCase.issueForCurrentUser(workerId);
        UUID firstJti = first.jti();
        assertThat(first.sp1()).startsWith("SP1.");

        // Segundo QR: rehidrata mismo JTI y SP1 (Ed25519 deterministico).
        IssueWorkerTokenUseCase.IssuedWorkerToken second = issueUseCase.issueForCurrentUser(workerId);
        assertThat(second.jti()).isEqualTo(firstJti);
        assertThat(second.sp1()).isEqualTo(first.sp1());

        // Baja de 2 de 3 lotes: aun hay 1 permiso -> worker_token sigue activo.
        updateUseCase.update(ownerActor, lot1, workerId, null, null, Boolean.FALSE);
        updateUseCase.update(ownerActor, lot2, workerId, null, null, Boolean.FALSE);
        assertRevokedInBarrio().doesNotContain(firstJti);

        // Baja del ultimo lote: worker_token revocado, JTI en /access/revoked.
        updateUseCase.update(ownerActor, lot3, workerId, null, null, Boolean.FALSE);
        assertRevokedInBarrio().contains(firstJti);

        // Reactivar en lot1 via POST con nueva franja.
        AddWorkerUseCase.Result reactivated = addUseCase.add(ownerActor, lot1,
                cmd(List.of(3, 5), LocalTime.of(14, 0), LocalTime.of(18, 0)),
                "Titular", "Barrio");
        assertThat(reactivated.outcome()).isEqualTo(AddWorkerUseCase.Outcome.REACTIVATED_PERMISSION);

        // Nuevo QR: JTI nuevo, distinto del anterior.
        IssueWorkerTokenUseCase.IssuedWorkerToken fresh = issueUseCase.issueForCurrentUser(workerId);
        UUID freshJti = fresh.jti();
        assertThat(freshJti).isNotEqualTo(firstJti);
        assertThat(fresh.sp1()).isNotEqualTo(first.sp1());

        // Baja del nuevo: nuevo JTI tambien revocado; viejo sigue listado.
        updateUseCase.update(ownerActor, lot1, workerId, null, null, Boolean.FALSE);
        assertRevokedInBarrio().contains(firstJti, freshJti);
    }

    private org.assertj.core.api.ListAssert<UUID> assertRevokedInBarrio() {
        List<UUID> jtis = listRevokedUseCase.list(guard, null).stream()
                .map(RevokedEntry::jti).toList();
        return assertThat(jtis);
    }
}
