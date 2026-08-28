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

    // Reads y writes sobre lot_permissions. Mapeo:
    //   family_members.is_owner       <- (permission_type = 'OWNER')
    //   family_members.can_invite     <- (attributes->>'can_invite')::boolean
    //   family_members.relation       <- attributes->>'relation'
    //
    // Filtro permission_type IN ('OWNER','MEMBER'): WORKER (Fase 13) no es un
    // family_member semantico.

    @Override
    public Optional<FamilyMember> find(UUID userId, UUID lotId) {
        return jdbc.sql("""
                SELECT user_id, lot_id,
                       attributes->>'relation'              AS relation,
                       (permission_type = 'OWNER')          AS is_owner,
                       (attributes->>'can_invite')::boolean AS can_invite,
                       active
                  FROM lot_permissions
                 WHERE user_id = :uid AND lot_id = :lid
                   AND permission_type IN ('OWNER','MEMBER')
                 ORDER BY CASE permission_type WHEN 'OWNER' THEN 0 ELSE 1 END
                 LIMIT 1
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .query(this::mapMember)
                .optional();
    }

    @Override
    public List<FamilyMember> findByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT user_id, lot_id,
                       attributes->>'relation'              AS relation,
                       (permission_type = 'OWNER')          AS is_owner,
                       (attributes->>'can_invite')::boolean AS can_invite,
                       active
                  FROM lot_permissions
                 WHERE lot_id = :lid
                   AND permission_type IN ('OWNER','MEMBER')
                 ORDER BY (permission_type = 'OWNER') DESC, created_at ASC
                """)
                .param("lid", lotId)
                .query(this::mapMember)
                .list();
    }

    @Override
    public void save(FamilyMember member) {
        jdbc.sql("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
                VALUES (:user, :lot, :type,
                        jsonb_build_object('can_invite', :canInvite::boolean, 'relation', :relation),
                        :active)
                """)
                .param("user", member.userId())
                .param("lot", member.lotId())
                .param("type", member.isOwner() ? "OWNER" : "MEMBER")
                .param("canInvite", member.canInvite())
                .param("relation", member.relation())
                .param("active", member.active())
                .update();
    }

    @Override
    public void update(UUID userId, UUID lotId, boolean canInvite, boolean active) {
        // A lo sumo una fila (owner o member) por (user, lot). No restrinjo
        // por permission_type: cubre el path de desactivacion, unico DELETE
        // efectivo del sistema.
        jdbc.sql("""
                UPDATE lot_permissions
                   SET attributes = jsonb_set(attributes, '{can_invite}', to_jsonb(:canInvite::boolean)),
                       active = :active,
                       updated_at = now()
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
