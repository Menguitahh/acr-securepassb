package com.securepass.modules.identity;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.FamilyMemberView;
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
        seedUser(ownerId, "owner@dev.local", "Titular Test");
        seedUser(memberId, "member@dev.local", "Miembro Test");
        seedUser(otherMemberId, "other@dev.local", "Otro Test");
        jdbc.update("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (?, ?, 'OWNER', TRUE, TRUE, TRUE), (?, ?, 'HIJO', FALSE, TRUE, TRUE),
                       (?, ?, NULL, FALSE, TRUE, TRUE)
                """, lotId, ownerId, lotId, memberId, lotId, otherMemberId);
        jdbc.update("""
                INSERT INTO memberships (id, user_id, neighborhood_id, role, active)
                VALUES (?, ?, ?, 'RESIDENT_OWNER', TRUE), (?, ?, ?, 'RESIDENT_MEMBER', TRUE)
                """, UUID.randomUUID(), ownerId, neighborhoodId,
                UUID.randomUUID(), memberId, neighborhoodId);
        seedInvitation(memberInvitationId, memberId);
        seedInvitation(otherInvitationId, otherMemberId);
    }

    @Test
    void darDeBajaRevocaSoloLasInvitacionesActivasDelMiembro() {
        CurrentUser owner = new CurrentUser(
                ownerId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));

        FamilyMemberView updated = updateUseCase.update(owner, lotId, memberId, null, false);

        assertThat(updated.active()).isFalse();
        assertThat(statusOf(memberInvitationId)).isEqualTo("REVOKED");
        assertThat(statusOf(otherInvitationId)).isEqualTo("ACTIVE");
        assertThat(revokedBy(memberInvitationId)).isEqualTo(ownerId);
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
