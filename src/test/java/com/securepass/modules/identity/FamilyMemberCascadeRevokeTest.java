package com.securepass.modules.identity;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.UpdateFamilyMemberUseCase;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FamilyMemberCascadeRevokeTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UpdateFamilyMemberUseCase updateUseCase;

    UUID neighborhoodId;
    UUID lotId;
    UUID ownerId;
    UUID memberId;
    UUID otherMemberId;
    UUID memberInvitationId;
    UUID otherInvitationId;

    @BeforeEach
    void seed() {
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        otherMemberId = UUID.randomUUID();
        memberInvitationId = UUID.randomUUID();
        otherInvitationId = UUID.randomUUID();

        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'Barrio Test', ?)",
                neighborhoodId, "barrio-" + UUID.randomUUID());
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'A-001')",
                lotId, neighborhoodId);
        // Emails con el UUID adentro: Testcontainers reusa el postgres entre
        // tests, un email fijo choca con users_email_key.
        seedUser(ownerId, "owner-" + ownerId + "@t.local", "Titular Test");
        seedUser(memberId, "member-" + memberId + "@t.local", "Miembro Test");
        seedUser(otherMemberId, "other-" + otherMemberId + "@t.local", "Otro Test");
        jdbc.update("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (?, ?, 'OWNER', '{"can_invite": true, "relation": "OWNER"}'::jsonb, TRUE),
                       (?, ?, 'MEMBER', '{"can_invite": true, "relation": "HIJO"}'::jsonb, TRUE),
                       (?, ?, 'MEMBER', '{"can_invite": true, "relation": null}'::jsonb, TRUE)
                """, ownerId, lotId, memberId, lotId, otherMemberId, lotId);
        jdbc.update("""
                INSERT INTO memberships (id, user_id, neighborhood_id, role, active)
                VALUES (?, ?, ?, 'ROLE_RESIDENT_OWNER', TRUE), (?, ?, ?, 'ROLE_RESIDENT_MEMBER', TRUE)
                """, UUID.randomUUID(), ownerId, neighborhoodId,
                UUID.randomUUID(), memberId, neighborhoodId);
        seedInvitation(memberInvitationId, memberId);
        seedInvitation(otherInvitationId, otherMemberId);
    }

    @Test
    void darDeBajaRevocaSoloLasInvitacionesActivasDelMiembro() {
        CurrentUser owner = new CurrentUser(
                ownerId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));

        UpdateFamilyMemberUseCase.Result result = updateUseCase.update(owner, lotId, memberId, null, false);

        assertThat(result.view().active()).isFalse();
        assertThat(result.outcome()).isEqualTo(UpdateFamilyMemberUseCase.Outcome.REVOKED_MEMBER);
        assertThat(result.revokedInvitationsCount()).isEqualTo(1);
        assertThat(statusOf(memberInvitationId)).isEqualTo("REVOKED");
        assertThat(statusOf(otherInvitationId)).isEqualTo("ACTIVE");
        assertThat(revokedBy(memberInvitationId)).isEqualTo(ownerId);
    }

    // Seguridad: el resident_pass del familiar tiene que caer en la cascada.
    // Es el QR permanente que YA ESTA en su telefono; si no lo revocamos, el
    // jti no aparece en /access/revoked, el device del guardia no se entera y
    // la persona sigue entrando. Ver comentario LOAD-BEARING en
    // ActiveInvitationJdbcRevoker.
    @Test
    void darDeBajaRevocaElResidentPassDelMiembro() {
        CurrentUser owner = new CurrentUser(
                ownerId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));
        UUID residentPassId = UUID.randomUUID();
        seedResidentPass(residentPassId, memberId);

        UpdateFamilyMemberUseCase.Result result = updateUseCase.update(owner, lotId, memberId, null, false);

        assertThat(result.outcome()).isEqualTo(UpdateFamilyMemberUseCase.Outcome.REVOKED_MEMBER);
        assertThat(result.revokedInvitationsCount()).isEqualTo(2); // regular + resident_pass
        assertThat(statusOf(residentPassId)).isEqualTo("REVOKED");
        assertThat(revokedBy(residentPassId)).isEqualTo(ownerId);
    }

    // Aislamiento: la cascada esta scopeada al lote del PATCH. Si el familiar
    // creo una invitacion en OTRO lote (por ser residente ahi), no se toca.
    @Test
    void darDeBajaNoTocaInvitacionesDelMismoUserEnOtroLote() {
        CurrentUser owner = new CurrentUser(
                ownerId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));
        UUID otherLotId = UUID.randomUUID();
        UUID crossLotInvitationId = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'B-002')",
                otherLotId, neighborhoodId);
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status, guest_name,
                    guest_document, valid_from, valid_until, max_uses, used_count, token_kid
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado', '30123456',
                          now() - interval '1 hour', now() + interval '1 hour', 1, 0, 'kid-test')
                """, crossLotInvitationId, neighborhoodId, otherLotId, memberId);

        updateUseCase.update(owner, lotId, memberId, null, false);

        assertThat(statusOf(memberInvitationId)).isEqualTo("REVOKED");
        assertThat(statusOf(crossLotInvitationId)).isEqualTo("ACTIVE");
    }

    private void seedResidentPass(UUID id, UUID userId) {
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status, guest_name,
                    guest_document, valid_from, valid_until,
                    max_uses, used_count, token_kid, resident_pass
                ) VALUES (?, ?, ?, ?, 'EVENT', 'ACTIVE', 'Residente', '00000000',
                          now() - interval '1 hour', '2099-12-31'::timestamptz,
                          1, 0, 'kid-pass', TRUE)
                """, id, neighborhoodId, lotId, userId);
    }

    private void seedUser(UUID id, String email, String name) {
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'hash', ?, 'ACTIVE')
                """, id, email, name);
    }

    private void seedInvitation(UUID id, UUID createdBy) {
        jdbc.update("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status, guest_name,
                    guest_document, valid_from, valid_until, max_uses, used_count, token_kid
                ) VALUES (?, ?, ?, ?, 'SINGLE', 'ACTIVE', 'Invitado', '30123456',
                          now() - interval '1 hour', now() + interval '1 hour', 1, 0, 'kid-test')
                """, id, neighborhoodId, lotId, createdBy);
    }

    private String statusOf(UUID invitationId) {
        return jdbc.queryForObject(
                "SELECT status FROM invitations WHERE id = ?", String.class, invitationId);
    }

    private UUID revokedBy(UUID invitationId) {
        return jdbc.queryForObject(
                "SELECT revoked_by FROM invitations WHERE id = ?", UUID.class, invitationId);
    }
}
