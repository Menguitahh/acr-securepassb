package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.FamilyMemberRepository;
import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Membership;
import com.securepass.modules.identity.domain.Role;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class FamilyMemberJdbcRepository implements FamilyMemberRepository {

    private final JdbcClient jdbc;

    public FamilyMemberJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<FamilyMember> find(UUID userId, UUID lotId) {
        return jdbc.sql("""
                SELECT user_id, lot_id, relation, is_owner, can_invite, active
                  FROM family_members
                 WHERE user_id = :uid AND lot_id = :lid
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .query(this::mapMember)
                .optional();
    }

    @Override
    public List<FamilyMember> findByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT user_id, lot_id, relation, is_owner, can_invite, active
                  FROM family_members
                 WHERE lot_id = :lid
                 ORDER BY is_owner DESC, created_at ASC
                """)
                .param("lid", lotId)
                .query(this::mapMember)
                .list();
    }

    @Override
    public void save(FamilyMember member) {
        jdbc.sql("""
                INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                VALUES (:lot, :user, :relation, :owner, :canInvite, :active)
                """)
                .param("lot", member.lotId())
                .param("user", member.userId())
                .param("relation", member.relation())
                .param("owner", member.isOwner())
                .param("canInvite", member.canInvite())
                .param("active", member.active())
                .update();
    }

    @Override
    public void update(UUID userId, UUID lotId, boolean canInvite, boolean active) {
        jdbc.sql("""
                UPDATE family_members
                   SET can_invite = :canInvite,
                       active = :active
                 WHERE user_id = :uid AND lot_id = :lid
                """)
                .param("canInvite", canInvite)
                .param("active", active)
                .param("uid", userId)
                .param("lid", lotId)
                .update();
    }

    @Override
    public UUID neighborhoodIdOfLot(UUID lotId) {
        return jdbc.sql("SELECT neighborhood_id FROM lots WHERE id = :id")
                .param("id", lotId)
                .query(UUID.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("lote inexistente " + lotId));
    }

    @Override
    public Optional<Membership> findMembership(UUID userId, UUID neighborhoodId, Role role) {
        return jdbc.sql("""
                SELECT id, user_id, neighborhood_id, role, active
                  FROM memberships
                 WHERE user_id = :uid AND neighborhood_id = :nid AND role = :role
                """)
                .param("uid", userId)
                .param("nid", neighborhoodId)
                .param("role", role.name())
                .query((rs, i) -> new Membership(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("neighborhood_id", UUID.class),
                        Role.valueOf(rs.getString("role")),
                        rs.getBoolean("active")))
                .optional();
    }

    @Override
    public void saveMembership(Membership membership) {
        jdbc.sql("""
                INSERT INTO memberships (id, user_id, neighborhood_id, role, active)
                VALUES (:id, :user, :neighborhood, :role, :active)
                """)
                .param("id", membership.id())
                .param("user", membership.userId())
                .param("neighborhood", membership.neighborhoodId())
                .param("role", membership.role().name())
                .param("active", membership.active())
                .update();
    }

    private FamilyMember mapMember(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new FamilyMember(
                rs.getObject("user_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getString("relation"),
                rs.getBoolean("is_owner"),
                rs.getBoolean("can_invite"),
                rs.getBoolean("active"));
    }
}
