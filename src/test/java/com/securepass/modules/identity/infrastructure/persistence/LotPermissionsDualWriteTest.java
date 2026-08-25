package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.FamilyMemberRepository;
import com.securepass.modules.identity.domain.FamilyMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS-106: la escritura dual family_members + lot_permissions vive en la
 * misma transaccion. Si el segundo write falla, la operacion entera hace
 * rollback y no queda drift entre las dos tablas.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LotPermissionsDualWriteTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired FamilyMemberRepository family;
    @Autowired PlatformTransactionManager txm;

    UUID neighborhoodId;
    UUID lotId;
    UUID userId;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        userId = UUID.randomUUID();
        jdbc.update("DELETE FROM lot_permissions");
        jdbc.update("DELETE FROM family_members");
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                neighborhoodId, "N-" + neighborhoodId.toString().substring(0, 8),
                "n-" + neighborhoodId.toString().substring(0, 8));
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lotId, neighborhoodId, "L-01");
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, userId, userId + "@t.local", "$argon2id$dummy", "Test User");
    }

    @Test
    void saveEscribeEnLasDosTablas() {
        new TransactionTemplate(txm).executeWithoutResult(status ->
                family.save(new FamilyMember(userId, lotId, "Hijo/a", false, true, true)));

        Integer fm = jdbc.queryForObject(
                "SELECT count(*) FROM family_members WHERE user_id = ? AND lot_id = ?",
                Integer.class, userId, lotId);
        Integer lp = jdbc.queryForObject(
                "SELECT count(*) FROM lot_permissions WHERE user_id = ? AND lot_id = ? AND permission_type = 'MEMBER'",
                Integer.class, userId, lotId);
        assertThat(fm).isEqualTo(1);
        assertThat(lp).isEqualTo(1);
    }

    @Test
    void updateReplicaCambiosEnLasDosTablas() {
        new TransactionTemplate(txm).executeWithoutResult(status ->
                family.save(new FamilyMember(userId, lotId, "Hijo/a", false, true, true)));

        new TransactionTemplate(txm).executeWithoutResult(status ->
                family.update(userId, lotId, false, false));

        Boolean fmActive = jdbc.queryForObject(
                "SELECT active FROM family_members WHERE user_id = ? AND lot_id = ?",
                Boolean.class, userId, lotId);
        Boolean lpActive = jdbc.queryForObject(
                "SELECT active FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                Boolean.class, userId, lotId);
        Boolean lpCanInvite = jdbc.queryForObject(
                "SELECT (attributes->>'can_invite')::boolean FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                Boolean.class, userId, lotId);
        assertThat(fmActive).isFalse();
        assertThat(lpActive).isFalse();
        assertThat(lpCanInvite).isFalse();
    }

    @Test
    void falloEnLotPermissionsRollbackeaFamilyMembers() {
        // Pre-sembramos una fila en lot_permissions que va a chocar contra el
        // UNIQUE (user_id, lot_id, permission_type) cuando save() intente
        // insertar. La primera parte del dual write (family_members) succeed;
        // la segunda dispara la constraint. La tx entera debe rollbackear.
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'MEMBER', '{}'::jsonb, true)
                """, userId, lotId);

        assertThatThrownBy(() ->
                new TransactionTemplate(txm).executeWithoutResult(status ->
                        family.save(new FamilyMember(userId, lotId, "Hijo/a", false, true, true))))
                .isInstanceOf(DataIntegrityViolationException.class);

        // family_members quedo vacio: el rollback deshizo el primer INSERT.
        Integer fm = jdbc.queryForObject(
                "SELECT count(*) FROM family_members WHERE user_id = ? AND lot_id = ?",
                Integer.class, userId, lotId);
        assertThat(fm).isEqualTo(0);

        // lot_permissions preserva la fila pre-sembrada, no la nueva.
        Integer lp = jdbc.queryForObject(
                "SELECT count(*) FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                Integer.class, userId, lotId);
        assertThat(lp).isEqualTo(1);
    }
}
