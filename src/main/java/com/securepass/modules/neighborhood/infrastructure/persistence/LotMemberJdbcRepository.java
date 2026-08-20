package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.application.LotMemberRepository;
import com.securepass.modules.neighborhood.domain.LotMember;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LotMemberJdbcRepository implements LotMemberRepository {

    private final JdbcClient jdbc;

    public LotMemberJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LotMember> findOwner(UUID lotId) {
        return jdbc.sql("""
                SELECT id, lot_id, user_id, relation, is_owner, can_invite, active, created_at
                  FROM family_members
                 WHERE lot_id = :lot AND is_owner = TRUE AND active = TRUE
                 LIMIT 1
                """)
                .param("lot", lotId).query(mapper()).optional();
    }

    @Override
    public Optional<LotMember> find(UUID lotId, UUID userId) {
        return jdbc.sql("""
                SELECT id, lot_id, user_id, relation, is_owner, can_invite, active, created_at
                  FROM family_members
                 WHERE lot_id = :lot AND user_id = :u
                """)
                .param("lot", lotId).param("u", userId).query(mapper()).optional();
    }

    @Override
    public List<LotMember> listByLot(UUID lotId) {
        return jdbc.sql("""
                SELECT id, lot_id, user_id, relation, is_owner, can_invite, active, created_at
                  FROM family_members
                 WHERE lot_id = :lot
                 ORDER BY is_owner DESC, created_at
                """)
                .param("lot", lotId).query(mapper()).list();
    }

    @Override
    public void save(LotMember m) {
        jdbc.sql("""
                INSERT INTO family_members (id, lot_id, user_id, relation, is_owner, can_invite, active, created_at)
                VALUES (:id, :lot, :u, :rel, :owner, :inv, :active, :created)
                """)
                .param("id", m.id())
                .param("lot", m.lotId())
                .param("u", m.userId())
                .param("rel", m.relation())
                .param("owner", m.isOwner())
                .param("inv", m.canInvite())
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
