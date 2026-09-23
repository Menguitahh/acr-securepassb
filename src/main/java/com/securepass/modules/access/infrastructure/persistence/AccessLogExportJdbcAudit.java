package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AccessLogExportAudit;
import com.securepass.modules.access.application.AccessLogQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@Repository
public class AccessLogExportJdbcAudit implements AccessLogExportAudit {

    private final JdbcClient jdbc;

    public AccessLogExportJdbcAudit(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID actorUserId, AccessLogQuery.ExportFilter f) {
        jdbc.sql("""
                INSERT INTO access_log_exports (neighborhood_id, actor_user_id, range_from, range_to, lot_id, result)
                VALUES (:nid, :actor, :from, :to, :lot, :result)
                """)
                .param("nid", f.neighborhoodId())
                .param("actor", actorUserId)
                .param("from", Timestamp.from(f.from()))
                .param("to", Timestamp.from(f.to()))
                .param("lot", f.lotIdFilter())
                .param("result", result(f.allowedOnly()))
                .update();
    }

    private static String result(Boolean allowedOnly) {
        if (allowedOnly == null) return null;
        return allowedOnly ? "ALLOWED" : "DENIED";
    }
}
