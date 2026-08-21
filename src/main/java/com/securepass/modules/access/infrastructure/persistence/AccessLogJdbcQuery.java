package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AccessLogQuery;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class AccessLogJdbcQuery implements AccessLogQuery {

    private final JdbcClient jdbc;

    public AccessLogJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page list(Filter f) {
        StringBuilder where = new StringBuilder("WHERE al.neighborhood_id = :nid");
        if (!f.lotIdsAllowed().isEmpty()) where.append(" AND al.lot_id = ANY(:lots)");
        if (f.lotIdFilter() != null) where.append(" AND al.lot_id = :lotFilter");
        if (f.from() != null) where.append(" AND al.captured_at >= :from");
        if (f.to() != null) where.append(" AND al.captured_at <= :to");
        if (f.allowedOnly() != null) {
            where.append(" AND al.result = :result");
        }

        var itemsQ = jdbc.sql("""
                SELECT al.id, al.lot_id, l.code AS lot_code,
                       al.direction, al.subject_type, al.result, al.denial_reason,
                       al.subject_name, al.subject_document, al.companions, al.captured_at
                  FROM access_logs al
                  LEFT JOIN lots l ON l.id = al.lot_id
                """ + where + """
                 ORDER BY al.captured_at DESC
                 LIMIT :size OFFSET :offset
                """);
        var countQ = jdbc.sql("SELECT COUNT(*) FROM access_logs al " + where);

        itemsQ.param("nid", f.neighborhoodId());
        countQ.param("nid", f.neighborhoodId());
        if (!f.lotIdsAllowed().isEmpty()) {
            UUID[] lots = f.lotIdsAllowed().toArray(new UUID[0]);
            itemsQ.param("lots", lots);
            countQ.param("lots", lots);
        }
        if (f.lotIdFilter() != null) {
            itemsQ.param("lotFilter", f.lotIdFilter());
            countQ.param("lotFilter", f.lotIdFilter());
        }
        if (f.from() != null) {
            itemsQ.param("from", Timestamp.from(f.from()));
            countQ.param("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            itemsQ.param("to", Timestamp.from(f.to()));
            countQ.param("to", Timestamp.from(f.to()));
        }
        if (f.allowedOnly() != null) {
            String result = f.allowedOnly() ? "ALLOWED" : "DENIED";
            itemsQ.param("result", result);
            countQ.param("result", result);
        }
        itemsQ.param("size", f.size()).param("offset", (long) f.page() * f.size());

        var items = itemsQ.query((rs, i) -> {
            String reason = rs.getString("denial_reason");
            return new Item(
                    rs.getObject("id", UUID.class),
                    rs.getObject("lot_id", UUID.class),
                    rs.getString("lot_code"),
                    Direction.valueOf(rs.getString("direction")),
                    SubjectType.valueOf(rs.getString("subject_type")),
                    "ALLOWED".equals(rs.getString("result")),
                    reason == null ? null : DenialReason.valueOf(reason),
                    rs.getString("subject_name"),
                    rs.getString("subject_document"),
                    rs.getInt("companions"),
                    rs.getTimestamp("captured_at").toInstant());
        }).list();
        long total = countQ.query(Long.class).single();
        return new Page(items, f.page(), f.size(), total);
    }
}
