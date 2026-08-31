package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.application.ListAccessLogsUseCase;
import com.securepass.modules.identity.application.AddFamilyMemberUseCase;
import com.securepass.modules.identity.application.ListFamilyMembersUseCase;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.CreateInvitationUseCase;
import com.securepass.modules.invitation.application.ExtendInvitationUseCase;
import com.securepass.modules.invitation.application.IssueResidentPassesUseCase;
import com.securepass.modules.invitation.application.RevokeInvitationUseCase;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.neighborhood.application.CreateLotUseCase;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Baseline IDOR intra-barrio cross-lote a nivel use-case (pre WBS-108).
 *
 * IdorIsolationTest ya cubre cross-barrio. Este suite verifica lo que
 * WBS-108 va a proteger explicitamente: un RESIDENT_OWNER del lote A NO
 * puede tocar recursos del lote B DENTRO DEL MISMO BARRIO.
 *
 * Escrito contra el modelo viejo (roles globales + defensas dispersas en
 * use cases). Cada test que falla aca es un agujero existente hoy, no uno
 * que introduzca WBS-108. Los tests que pasan indican que la defensa esta
 * en el use case y hay que preservarla al mover a permisos por lote.
 *
 * ALCANCE: verifica que el use case rechaza. La verificacion HTTP-level
 * (incluye @PreAuthorize + serializacion) esta en IdorIntraBarrioHttpTest.
 * Cuando WBS-108 mueva la defensa al gate, este suite puede ver cambios
 * de tipo de excepcion (AccessDeniedException vs otra) — evaluar caso
 * por caso, no relajar el matcher por conveniencia.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IdorIntraBarrioTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired CreateLotUseCase createLot;
    @Autowired CreateInvitationUseCase createInvitation;
    @Autowired RevokeInvitationUseCase revokeInvitation;
    @Autowired ExtendInvitationUseCase extendInvitation;
    @Autowired ListFamilyMembersUseCase listFamily;
    @Autowired AddFamilyMemberUseCase addFamily;
    @Autowired ListAccessLogsUseCase listLogs;
    @Autowired IssueResidentPassesUseCase issuePasses;
    @Autowired SigningKeyIssuer signingKeyIssuer;

    UUID barrio;
    UUID gate;
    UUID guardId;
    UUID residentA;
    UUID residentB;
    Lot lotA;
    Lot lotB;
    UUID invitationB;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        guardId = UUID.randomUUID();
        residentA = UUID.randomUUID();
        residentB = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'X', ?)",
                barrio, "n-" + UUID.randomUUID());
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'g', 'BOTH')",
                gate, barrio);
        // Signing key requerida por IssueResidentPassesUseCase.
        signingKeyIssuer.issueForNeighborhood(barrio);
        insertUser(guardId);
        insertUser(residentA);
        insertUser(residentB);

        // Mismo barrio, dos lotes distintos.
        lotA = createLot.create(barrio, new CreateLotUseCase.Command("A-1", null, null));
        lotB = createLot.create(barrio, new CreateLotUseCase.Command("B-1", null, null));

        // residentA es owner de lotA; residentB es owner de lotB.
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, residentA, lotA.id());

        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
                """, residentB, lotB.id());

        // Invitation activa en lotB creada por residentB (para revocar/extender).
        invitationB = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, valid_from, valid_until,
                    max_uses, used_count, token_kid, jti
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Maria', '30123456', 0, ?, ?, 1, 0, 'kid-b', ?)
                """, invitationB, barrio, lotB.id(), residentB,
                Timestamp.from(now.minusSeconds(60)),
                Timestamp.from(now.plusSeconds(3600)), UUID.randomUUID());

        // Access log en lotB (para el filtro).
        UUID logB = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, guard_id, lot_id,
                    direction, subject_type, result, subject_name, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, ?, 'IN', 'MANUAL', 'ALLOWED', 'X', ?, ?)
                """, logB, barrio, gate, guardId, lotB.id(),
                Timestamp.from(now), UUID.randomUUID());
    }

    private CurrentUser actorResidentA() {
        return new CurrentUser(residentA, barrio, List.of(Role.RESIDENT_OWNER), List.of(lotA.id()));
    }

    // ---------- 1. crear invitacion con lotB ----------
    @Test
    void residentAcrearInvitacionEnLotB_rechaza() {
        assertThatThrownBy(() -> createInvitation.create(residentA, barrio,
                new CreateInvitationUseCase.Command(
                        lotB.id(), InvitationType.SINGLE,
                        "Guest", "30000000", 0, null,
                        Instant.now(), Instant.now().plus(1, ChronoUnit.HOURS),
                        1)))
                .as("residentA (owner de lotA) NO debe poder crear invitacion sobre lotB")
                .isInstanceOfAny(
                        org.springframework.security.access.AccessDeniedException.class,
                        com.securepass.shared.exception.BusinessRuleViolationException.class,
                        com.securepass.shared.exception.ResourceNotFoundException.class);
    }

    // ---------- 2. revocar invitacion de lotB ----------
    @Test
    void residentArevocarInvitacionDeLotB_rechaza() {
        assertThatThrownBy(() -> revokeInvitation.revoke(actorResidentA(), invitationB))
                .as("residentA NO debe poder revocar invitacion de lotB")
                .isInstanceOfAny(
                        org.springframework.security.access.AccessDeniedException.class,
                        com.securepass.shared.exception.ResourceNotFoundException.class);
    }

    // ---------- 3. extender invitacion de lotB ----------
    @Test
    void residentAextenderInvitacionDeLotB_rechaza() {
        assertThatThrownBy(() -> extendInvitation.extend(barrio, residentA, invitationB))
                .as("residentA NO debe poder extender invitacion de lotB")
                .isInstanceOfAny(
                        org.springframework.security.access.AccessDeniedException.class,
                        com.securepass.shared.exception.ResourceNotFoundException.class,
                        com.securepass.shared.exception.BusinessRuleViolationException.class);
    }

    // ---------- 4. listar familia de lotB ----------
    @Test
    void residentAlistarFamiliaDeLotB_rechaza() {
        assertThatThrownBy(() -> listFamily.list(actorResidentA(), lotB.id()))
                .as("residentA NO debe poder listar familia de lotB")
                .isInstanceOfAny(
                        org.springframework.security.access.AccessDeniedException.class,
                        com.securepass.shared.exception.ResourceNotFoundException.class);
    }

    // ---------- 5. agregar miembro a lotB ----------
    // WBS-108 movio la defensa al gate (@PreAuthorize lotPerm.hasAtLeast OWNER)
    // y WBS-110 cambio el contrato del alta (por DNI + activacion, no por
    // identifier). El use case ya no valida ownership: la cobertura vive en
    // IdorIntraBarrioHttpTest.POST_family_lots_lotB_members. Sin gate en el
    // use case, cualquier test aca seria una defensa que no existe.

    // ---------- 6. leer access logs filtrando por lotB ----------
    @Test
    void residentAleerAccessLogsFiltrandoPorLotB_rechaza() {
        assertThatThrownBy(() -> listLogs.list(actorResidentA(), null, null, lotB.id(), null, 0))
                .as("residentA NO debe poder filtrar access logs por lotB")
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // Y sin filtro explicito: la lista NO debe incluir logs de lotB.
    @Test
    void residentAlistarAccessLogsSinFiltro_noVeLotB() {
        var page = listLogs.list(actorResidentA(), null, null, null, null, 0);
        assertThat(page.items())
                .as("residentA sin filtro no debe ver logs de lotB")
                .allSatisfy(item -> assertThat(item.lotId()).isEqualTo(lotA.id()));
    }

    // ---------- 7. ver pases: residentA no debe recibir pase de lotB ----------
    @Test
    void residentAverPases_noIncluyeLotB() {
        var passes = issuePasses.issue(residentA, barrio);
        assertThat(passes)
                .as("los pases de residentA no deben incluir lotB")
                .noneMatch(p -> p.lotId().equals(lotB.id()));
    }

    private void insertUser(UUID id) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'X', 'ACTIVE')
                """, id, id + "@t.com");
    }
}
