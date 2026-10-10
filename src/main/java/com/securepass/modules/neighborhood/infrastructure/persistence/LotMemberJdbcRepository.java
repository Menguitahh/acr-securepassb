package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.application.LotMemberRepository;
import com.securepass.modules.neighborhood.domain.LotMember;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class LotMemberJdbcRepository implements LotMemberRepository {

    private final JdbcClient jdbc;

    // Reads y writes sobre lot_permissions (ver FamilyMemberJdbcRepository
    // para el mapeo). El campo `id` es el id de la fila de lot_permissions;
    // ningun caller lo usa como referencia estable fuera del domain object
    // LotMember.

    @Override
    public Optional<LotMember> findOwner(UUID lotId) {
        return jdbc.sql("""
                SELECT id, lot_id, user_id,
                       attributes->>'relation'              AS relation,
                       (permission_type = 'OWNER')          AS is_owner,
                       (attributes->>'can_invite')::boolean AS can_invite,
                       active, created_at
                  FROM lot_permissions
                 WHERE lot_id = :lot AND permission_type = 'OWNER' AND active = TRUE
                 LIMIT 1
                """)
                .param("lot", lotId).query(mapper()).optional();
    }

    @Override
    public Optional<LotMember> find(UUID lotId, UUID userId) {
        return jdbc.sql("""
                SELECT id, lot_id, user_id,
                       attributes->>'relation'              AS relation,
                       (permission_type = 'OWNER')          AS is_owner,
                       (attributes->>'can_invite')::boolean AS can_invite,
                       active, created_at
                  FROM lot_permissions
                 WHERE lot_id = :lot AND user_id = :u
                   AND permission_type IN ('OWNER','MEMBER')
                 ORDER BY CASE permission_type WHEN 'OWNER' THEN 0 ELSE 1 END
                 LIMIT 1
                """)
                .param("lot", lotId).param("u", userId).query(mapper()).optional();
    }

    @Override
    public List<LotMember> listByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT id, lot_id, user_id,
                       attributes->>'relation'              AS relation,
                       (permission_type = 'OWNER')          AS is_owner,
                       (attributes->>'can_invite')::boolean AS can_invite,
                       active, created_at
                  FROM lot_permissions
                 WHERE lot_id = :lot
                   AND permission_type IN ('OWNER','MEMBER')
                 ORDER BY (permission_type = 'OWNER') DESC, created_at
                """)
                .param("lot", lotId).query(mapper()).list();
    }

    @Override
    public void save(LotMember m) {
        jdbc.sql("""
                INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active, created_at)
                VALUES (:u, :lot, :type,
                        jsonb_build_object('can_invite', :inv::boolean, 'relation', :rel),
                        :active, :created)
                """)
                .param("u", m.userId())
                .param("lot", m.lotId())
                .param("type", m.isOwner() ? "OWNER" : "MEMBER")
                .param("inv", m.canInvite())
                .param("rel", m.relation())
                .param("active", m.active())
                .param("created", java.sql.Timestamp.from(m.createdAt()))
                .update();
    }

    private RowMapper<LotMember> mapper() {
        return (rs, i) -> new LotMember(
                rs.getObject("id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("relation"),
                rs.getBoolean("is_owner"),
                rs.getBoolean("can_invite"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant());
    }
}
