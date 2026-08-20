package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.InvitationPage;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.RevokedEntry;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class InvitationJdbcRepository implements InvitationRepository {

    private final JdbcClient jdbc;

    public InvitationJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Invitation inv) {
        jdbc.sql("""
                INSERT INTO invitations (
                    id, neighborhood_id, lot_id, created_by, type, status,
                    guest_name, guest_document, companions, notes,
                    valid_from, valid_until, weekdays, daily_from, daily_until,
                    max_uses, used_count, token_kid, jti, resident_pass, created_at, updated_at
                ) VALUES (
                    :id, :nid, :lid, :by, :type, :status,
                    :name, :doc, :comp, :notes,
                    :vfrom, :vuntil, CAST(:wdays AS SMALLINT[]), :dfrom, :duntil,
                    :max, :used, :kid, :jti, :resident, :created, :created
                )
                """)
                .param("id", inv.id())
                .param("nid", inv.neighborhoodId())
                .param("lid", inv.lotId())
                .param("by", inv.createdBy())
                .param("type", inv.type().name())
                .param("status", inv.status().name())
                .param("name", inv.guestName())
                .param("doc", inv.guestDocument())
                .param("comp", inv.companions())
                .param("notes", inv.notes())
                .param("vfrom", Timestamp.from(inv.validFrom()))
                .param("vuntil", Timestamp.from(inv.validUntil()))
                .param("wdays", weekdaysLiteral(inv.weekdays()))
                .param("dfrom", inv.dailyFrom() == null ? null : Time.valueOf(inv.dailyFrom()))
                .param("duntil", inv.dailyUntil() == null ? null : Time.valueOf(inv.dailyUntil()))
                .param("max", inv.maxUses())
                .param("used", inv.usedCount())
                .param("kid", inv.tokenKid())
                .param("jti", inv.jti())
                .param("resident", inv.residentPass())
                .param("created", Timestamp.from(inv.createdAt()))
                .update();
    }

    @Override
    public void updateStatus(UUID id, InvitationStatus status, UUID revokedBy) {
        jdbc.sql("""
                UPDATE invitations
                   SET status = :status,
                       revoked_at = CASE WHEN :status = 'REVOKED' THEN now() ELSE revoked_at END,
                       revoked_by = CASE WHEN :status = 'REVOKED' THEN :by ELSE revoked_by END,
                       updated_at = now()
                 WHERE id = :id
                """)
                .param("id", id)
                .param("status", status.name())
                .param("by", revokedBy)
                .update();
    }

    @Override
    public Optional<Invitation> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, lot_id, created_by, type, status,
                       guest_name, guest_document, companions, notes,
                       valid_from, valid_until, weekdays, daily_from, daily_until,
                       max_uses, used_count, token_kid, jti, resident_pass, created_at
                  FROM invitations
                 WHERE id = :id
                """)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public Optional<Invitation> findByIdForUpdate(UUID id) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, lot_id, created_by, type, status,
                       guest_name, guest_document, companions, notes,
                       valid_from, valid_until, weekdays, daily_from, daily_until,
                       max_uses, used_count, token_kid, jti, resident_pass, created_at
                  FROM invitations
                 WHERE id = :id
                 FOR UPDATE
                """)
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public void persistConsumption(Invitation inv) {
        jdbc.sql("""
                UPDATE invitations
                   SET used_count = :used, status = :status, updated_at = now()
                 WHERE id = :id
                """)
                .param("id", inv.id())
                .param("used", inv.usedCount())
                .param("status", inv.status().name())
                .update();
    }

    @Override
    public InvitationPage list(ListQuery q) {
        StringBuilder where = new StringBuilder("WHERE neighborhood_id = :nid");
        if (!q.lotIdsAllowed().isEmpty()) where.append(" AND lot_id = ANY(:lots)");
        if (q.lotIdFilter() != null) where.append(" AND lot_id = :lotFilter");
        if (q.statusFilter() != null) where.append(" AND status = :status");

        var itemsQuery = jdbc.sql("""
                SELECT id, neighborhood_id, lot_id, created_by, type, status,
                       guest_name, guest_document, companions, notes,
                       valid_from, valid_until, weekdays, daily_from, daily_until,
                       max_uses, used_count, token_kid, jti, resident_pass, created_at
                  FROM invitations
                """ + where + """
                 ORDER BY created_at DESC
                 LIMIT :size OFFSET :offset
                """);
        var countQuery = jdbc.sql("SELECT COUNT(*) FROM invitations " + where);

        itemsQuery.param("nid", q.neighborhoodId());
        countQuery.param("nid", q.neighborhoodId());
        if (!q.lotIdsAllowed().isEmpty()) {
            UUID[] lots = q.lotIdsAllowed().toArray(new UUID[0]);
            itemsQuery.param("lots", lots);
            countQuery.param("lots", lots);
        }
        if (q.lotIdFilter() != null) {
            itemsQuery.param("lotFilter", q.lotIdFilter());
            countQuery.param("lotFilter", q.lotIdFilter());
        }
        if (q.statusFilter() != null) {
            itemsQuery.param("status", q.statusFilter().name());
            countQuery.param("status", q.statusFilter().name());
        }
        itemsQuery.param("size", q.size()).param("offset", q.page() * q.size());

        List<Invitation> items = itemsQuery.query(this::mapRow).list();
        long total = countQuery.query(Long.class).single();
        return new InvitationPage(items, q.page(), q.size(), total);
    }

    @Override
    public Optional<Invitation> findActiveResidentPass(UUID userId, UUID lotId) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, lot_id, created_by, type, status,
                       guest_name, guest_document, companions, notes,
                       valid_from, valid_until, weekdays, daily_from, daily_until,
                       max_uses, used_count, token_kid, jti, resident_pass, created_at
                  FROM invitations
                 WHERE created_by = :uid AND lot_id = :lid
                   AND resident_pass = TRUE AND status = 'ACTIVE'
                 LIMIT 1
                """)
                .param("uid", userId)
                .param("lid", lotId)
                .query(this::mapRow)
                .optional();
    }

    @Override
    public int expireOverdue(Instant cutoff) {
        return jdbc.sql("""
                UPDATE invitations
                   SET status = 'EXPIRED', updated_at = now()
                 WHERE status IN ('ACTIVE', 'USED')
                   AND valid_until < :cutoff
                   AND resident_pass = FALSE
                """)
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    @Override
    public List<RevokedEntry> listRevoked(UUID neighborhoodId, Instant since, int limit) {
        String sinceClause = since == null ? "" : " AND revoked_at > :since";
        var query = jdbc.sql("""
                SELECT jti, revoked_at
                  FROM invitations
                 WHERE neighborhood_id = :nid
                   AND status = 'REVOKED'
                """ + sinceClause + """
                 ORDER BY revoked_at ASC
                 LIMIT :limit
                """)
                .param("nid", neighborhoodId)
                .param("limit", limit);
        if (since != null) query.param("since", Timestamp.from(since));
        return query.query((rs, i) -> new RevokedEntry(
                        rs.getObject("jti", UUID.class),
                        rs.getTimestamp("revoked_at").toInstant()))
                .list();
    }

    private Invitation mapRow(ResultSet rs, int rowNum) throws SQLException {
        Time dailyFrom = rs.getTime("daily_from");
        Time dailyUntil = rs.getTime("daily_until");
        Array wdays = rs.getArray("weekdays");
        return new Invitation(
                rs.getObject("id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getObject("lot_id", UUID.class),
                rs.getObject("created_by", UUID.class),
                InvitationType.valueOf(rs.getString("type")),
                InvitationStatus.valueOf(rs.getString("status")),
                rs.getString("guest_name"),
                rs.getString("guest_document"),
                rs.getInt("companions"),
                rs.getString("notes"),
                rs.getTimestamp("valid_from").toInstant(),
                rs.getTimestamp("valid_until").toInstant(),
                weekdaysFromArray(wdays),
                dailyFrom == null ? null : dailyFrom.toLocalTime(),
                dailyUntil == null ? null : dailyUntil.toLocalTime(),
                rs.getInt("max_uses"),
                rs.getInt("used_count"),
                rs.getString("token_kid"),
                rs.getObject("jti", UUID.class),
                rs.getBoolean("resident_pass"),
                rs.getTimestamp("created_at").toInstant());
    }

    private static String weekdaysLiteral(List<Integer> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) return null;
        return weekdays.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
    }

    private static List<Integer> weekdaysFromArray(Array array) throws SQLException {
        if (array == null) return null;
        Object raw = array.getArray();
        if (raw == null) return null;
        List<Integer> out = new ArrayList<>();
        for (Object item : (Object[]) raw) {
            if (item != null) out.add(((Number) item).intValue());
        }
        return out.isEmpty() ? null : out;
    }
}
