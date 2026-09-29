package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AccessLogQuery;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Consumer;

@Repository
public class AccessLogJdbcQuery implements AccessLogQuery {

    private static final int EXPORT_FETCH_SIZE = 500;

    private final JdbcClient jdbc;
    private final JdbcClient streaming;

    public AccessLogJdbcQuery(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(EXPORT_FETCH_SIZE);
        this.streaming = JdbcClient.create(template);
    }

    // [from, to), igual que export y metricas (#199). El WHERE lo comparten
    // items y count.
    @Override
    public Page list(Filter f) {
        StringBuilder where = new StringBuilder("WHERE al.neighborhood_id = :nid");
        if (!f.lotIdsAllowed().isEmpty()) where.append(" AND al.lot_id = ANY(:lots)");
        if (f.lotIdFilter() != null) where.append(" AND al.lot_id = :lotFilter");
        if (f.from() != null) where.append(" AND al.captured_at >= :from");
        if (f.to() != null) where.append(" AND al.captured_at < :to");
        if (f.allowedOnly() != null) {
            where.append(" AND al.result = :result");
        }
        if (f.reason() != null) where.append(" AND al.denial_reason = :reason");

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
        if (f.reason() != null) {
            itemsQ.param("reason", f.reason().name());
            countQ.param("reason", f.reason().name());
        }
        itemsQ.param("size", f.size()).param("offset", (long) f.page() * f.size());

        var items = itemsQ.query((rs, i) -> map(rs)).list();
        long total = countQ.query(Long.class).single();
        return new Page(items, f.page(), f.size(), total);
    }

    // [from, to), igual que el front y las metricas (#197).
    // Cursor server-side: Postgres solo respeta fetchSize dentro de una
    // transaccion; sin ella trae el rango entero a memoria antes de escribir.
    @Override
    @Transactional(readOnly = true)
    public void forEach(ExportFilter f, Consumer<Item> sink) {
        var q = bind(streaming.sql("""
                SELECT al.id, al.lot_id, l.code AS lot_code,
                       al.direction, al.subject_type, al.result, al.denial_reason,
                       al.subject_name, al.subject_document, al.companions, al.captured_at
                  FROM access_logs al
                  LEFT JOIN lots l ON l.id = al.lot_id
                """ + exportWhere(f) + """
                 ORDER BY al.captured_at, al.id
                """), f);
        q.query((RowCallbackHandler) rs -> sink.accept(map(rs)));
    }

    // Mismo WHERE que forEach: el tope del XLSX tiene que contar las filas que
    // despues se escriben (#195).
    @Override
    public long count(ExportFilter f) {
        return bind(jdbc.sql("SELECT count(*) FROM access_logs al " + exportWhere(f)), f)
                .query(Long.class).single();
    }

    private static String exportWhere(ExportFilter f) {
        StringBuilder where = new StringBuilder(
                "WHERE al.neighborhood_id = :nid AND al.captured_at >= :from AND al.captured_at < :to");
        if (f.lotIdFilter() != null) where.append(" AND al.lot_id = :lotFilter");
        if (f.allowedOnly() != null) where.append(" AND al.result = :result");
        return where.toString();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec q, ExportFilter f) {
        q.param("nid", f.neighborhoodId())
                .param("from", Timestamp.from(f.from()))
                .param("to", Timestamp.from(f.to()));
        if (f.lotIdFilter() != null) q.param("lotFilter", f.lotIdFilter());
        if (f.allowedOnly() != null) q.param("result", f.allowedOnly() ? "ALLOWED" : "DENIED");
        return q;
    }

    @Override
    public ZoneId timezone(UUID neighborhoodId) {
        String tz = jdbc.sql("SELECT timezone FROM neighborhoods WHERE id = :nid")
                .param("nid", neighborhoodId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("neighborhood " + neighborhoodId + " no existe"));
        return ZoneId.of(tz);
    }

    private static Item map(ResultSet rs) throws SQLException {
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
    }
}
