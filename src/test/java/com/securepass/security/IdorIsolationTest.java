package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.application.CloseStayManuallyUseCase;
import com.securepass.modules.access.application.ListAccessLogsUseCase;
import com.securepass.modules.access.application.ListOpenStaysUseCase;
import com.securepass.modules.access.application.ManualAccessUseCase;
import com.securepass.modules.access.application.OpenShiftQuery;
import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.CreateInvitationUseCase;
import com.securepass.modules.invitation.application.ExtendInvitationUseCase;
import com.securepass.modules.invitation.application.GetInvitationUseCase;
import com.securepass.modules.invitation.application.IssueResidentPassesUseCase;
import com.securepass.modules.invitation.application.ListInvitationsUseCase;
import com.securepass.modules.invitation.application.RevokeInvitationUseCase;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.neighborhood.application.AddLotMemberUseCase;
import com.securepass.modules.neighborhood.application.CreateLotUseCase;
import com.securepass.modules.neighborhood.application.UpdateLotUseCase;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.modules.notification.application.NotifyResidentOfStayUseCase;
import com.securepass.modules.shift.application.ListShiftsUseCase;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IDOR (spec 5.5): "Todo endpoint valida `neighborhoodId` del JWT contra el
 * recurso solicitado". Un actor del barrio A NO puede ver ni tocar recursos
 * del barrio B via ninguna ruta del backend. Cubre todas las rutas que
 * reciben un id de recurso en el path o que filtran por barrio.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdorIsolationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired CreateLotUseCase createLot;
    @Autowired UpdateLotUseCase updateLot;
    @Autowired AddLotMemberUseCase addLotMember;
    @Autowired CloseStayManuallyUseCase closeStay;
    @Autowired NotifyResidentOfStayUseCase notifyResident;
    @Autowired ListOpenStaysUseCase listOpenStays;
    @Autowired StayRepository stays;
    @Autowired ListAccessLogsUseCase listLogs;
    @Autowired CreateInvitationUseCase createInvitation;
    @Autowired GetInvitationUseCase getInvitation;
    @Autowired RevokeInvitationUseCase revokeInvitation;
    @Autowired ExtendInvitationUseCase extendInvitation;
    @Autowired ListInvitationsUseCase listInvitations;
    @Autowired IssueResidentPassesUseCase issuePasses;
    @Autowired ManualAccessUseCase manualAccess;
    @Autowired ListShiftsUseCase listShifts;

    // El shift lookup en manual access requiere turno abierto: mockearlo a
    // true para no seedear turnos y aislar la prueba al IDOR.
    @MockBean OpenShiftQuery openShifts;

    UUID barrioA;
    UUID barrioB;
    UUID gateA;
    UUID gateB;
    UUID guardA;
    UUID residentA;
    UUID residentB;
    Lot lotA;
    Lot lotB;
    UUID invitationB;
    UUID stayB;

    @BeforeEach
    void seed() {
        barrioA = UUID.randomUUID();
        barrioB = UUID.randomUUID();
        gateA = UUID.randomUUID();
        gateB = UUID.randomUUID();
        guardA = UUID.randomUUID();
        residentA = UUID.randomUUID();
        residentB = UUID.randomUUID();

        Mockito.when(openShifts.hasOpenShift(Mockito.any())).thenReturn(true);

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'A', ?)",
                barrioA, "a-" + UUID.randomUUID());
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                barrioB, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'g', 'BOTH')",
                gateA, barrioA);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'g', 'BOTH')",
                gateB, barrioB);
        insertUser(guardA, "guard-a-" + UUID.randomUUID() + "@t.com");
        insertUser(residentA, "res-a-" + UUID.randomUUID() + "@t.com");
        insertUser(residentB, "res-b-" + UUID.randomUUID() + "@t.com");

        lotA = createLot.create(barrioA, new CreateLotUseCase.Command("A-1", null, null));
        lotB = createLot.create(barrioB, new CreateLotUseCase.Command("B-1", null, null));

        // Residente B es family_member en su barrio.
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'OWNER', TRUE, TRUE, TRUE)
                """, lotB.id(), residentB);

        // Invitation y stay abierta en barrio B, para que A intente tocarlas.
        invitationB = UUID.randomUUID();
        UUID invitationBJti = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, 'kid-b', ?)
                """, invitationB, barrioB, lotB.id(), residentB,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), invitationBJti);

        stayB = UUID.randomUUID();
        UUID entryLog = insertAccessLog(barrioB, gateB, lotB.id());
        jdbc.update("""
                INSERT INTO stays (id, neighborhood_id, invitation_id, lot_id,
                                   subject_name, entry_log_id, entered_at, expected_until)
                VALUES (?, ?, ?, ?, 'Maria', ?, now(), now() + interval '1 hour')
                """, stayB, barrioB, invitationB, lotB.id(), entryLog);
    }

    private CurrentUser adminA() {
        return new CurrentUser(UUID.randomUUID(), barrioA, List.of(Role.ADMIN), List.of());
    }

    private CurrentUser residentActorA() {
        return new CurrentUser(residentA, barrioA, List.of(Role.RESIDENT_OWNER), List.of(lotA.id()));
    }

    // ---------- lote (WBS-11) ----------

    @Test
    void updateLotCrossBarrio_404() {
        assertThatThrownBy(() -> updateLot.update(barrioA, lotB.id(),
                new UpdateLotUseCase.Patch(LotStatus.SUSPENDED, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addLotMemberCrossBarrio_404() {
        assertThatThrownBy(() -> addLotMember.add(barrioA, lotB.id(),
                new AddLotMemberUseCase.Command(residentA, "Hijo/a", false, true)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- stays (WBS-65..70) ----------

    @Test
    void closeStayCrossBarrio_404() {
        assertThatThrownBy(() -> closeStay.close(barrioA, gateA, guardA, stayB))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(stays.findById(stayB).orElseThrow().isOpen()).isTrue();
    }

    @Test
    void notifyResidentCrossBarrio_404() {
        assertThatThrownBy(() -> notifyResident.notify(barrioA, stayB))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listOpenStaysCrossBarrio_soloVeLasPropias() {
        var result = listOpenStays.list(barrioA);
        assertThat(result).noneMatch(v -> v.id().equals(stayB));
    }

    // ---------- access logs (WBS-28) ----------

    @Test
    void residenteVeAccessLogsSoloDeSusLotes() {
        insertAccessLog(barrioA, gateA, lotA.id());
        insertAccessLog(barrioB, gateB, lotB.id());
        var page = listLogs.list(residentActorA(), null, null, null, null, 0);
        assertThat(page.items()).allSatisfy(item -> assertThat(item.lotId()).isEqualTo(lotA.id()));
    }

    @Test
    void adminNoVeAccessLogsDeOtroBarrio() {
        insertAccessLog(barrioB, gateB, lotB.id());
        var page = listLogs.list(adminA(), null, null, null, null, 0);
        assertThat(page.items()).noneMatch(item -> item.lotId() != null && item.lotId().equals(lotB.id()));
    }

    // ---------- invitations (WBS-17..21, WBS-71) ----------

    @Test
    void createInvitationConLotDeOtroBarrio_422() {
        assertThatThrownBy(() -> createInvitation.create(residentA, barrioA,
                new CreateInvitationUseCase.Command(
                        lotB.id(), InvitationType.SINGLE,
                        "X", "30123456", 0, null,
                        Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS),
                        null, null, null, 1)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void getInvitationCrossBarrio_403() {
        assertThatThrownBy(() -> getInvitation.get(adminA(), invitationB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void revokeInvitationCrossBarrio_403() {
        assertThatThrownBy(() -> revokeInvitation.revoke(adminA(), invitationB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void extendInvitationCrossBarrio_404() {
        assertThatThrownBy(() -> extendInvitation.extend(barrioA, residentA, invitationB))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listInvitationsCrossBarrio_soloVeLasPropias() {
        var page = listInvitations.list(residentActorA(), null, null, 0);
        assertThat(page.items()).noneMatch(inv -> inv.id().equals(invitationB));
    }

    // ---------- resident passes (WBS-21) ----------

    @Test
    void issueResidentPassesCrossBarrio_noVeLotesAjenos() {
        var passes = issuePasses.issue(residentB, barrioA);
        assertThat(passes).noneMatch(p -> p.lotId().equals(lotB.id()));
    }

    // ---------- manual access (WBS-26) ----------

    @Test
    void manualAccessConLotDeOtroBarrio_404() {
        assertThatThrownBy(() -> manualAccess.record(new ManualAccessUseCase.Command(
                barrioA, gateA, guardA, lotB.id(),
                "Juan", "30123456", UUID.randomUUID(), Instant.now())))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- shifts (WBS-27) ----------

    @Test
    void listShiftsCrossBarrio_soloVeLosDelBarrioA() {
        UUID shiftB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at)
                VALUES (?, ?, ?, ?, now())
                """, shiftB, barrioB, gateB, residentB);
        var shifts = listShifts.list(barrioA, null, null, null);
        assertThat(shifts).noneMatch(s -> s.id().equals(shiftB));
    }

    // ---------- helpers ----------

    private void insertUser(UUID id, String email) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'X', 'ACTIVE')
                """, id, email);
    }

    private UUID insertAccessLog(UUID neighborhoodId, UUID gateId, UUID lotId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, guard_id, lot_id,
                    direction, subject_type, result,
                    subject_name, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, ?, 'IN', 'MANUAL', 'ALLOWED', 'X', ?, ?)
                """, id, neighborhoodId, gateId, guardA, lotId,
                Timestamp.from(Instant.now()), UUID.randomUUID());
        return id;
    }
}
