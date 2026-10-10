package com.securepass.modules.access.infrastructure.persistence;

import com.securepass.modules.access.application.AccessLogExportAudit;
import com.securepass.modules.access.application.AccessLogQuery;
import com.securepass.modules.access.application.ExportFormat;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class AccessLogExportJdbcAudit implements AccessLogExportAudit {

    private final JdbcClient jdbc;

    @Override
    public void record(UUID actorUserId, AccessLogQuery.ExportFilter f, ExportFormat format) {
        jdbc.sql("""
                INSERT INTO access_log_exports (neighborhood_id, actor_user_id, range_from, range_to, lot_id, result, format)
                VALUES (:nid, :actor, :from, :to, :lot, :result, :format)
                """)
                .param("nid", f.neighborhoodId())
                .param("actor", actorUserId)
                .param("from", Timestamp.from(f.from()))
                .param("to", Timestamp.from(f.to()))
                .param("lot", f.lotIdFilter())
                .param("result", result(f.allowedOnly()))
                .param("format", format.name())
                .update();
    }

    private static String result(Boolean allowedOnly) {
        if (allowedOnly == null) return null;
        return allowedOnly ? "ALLOWED" : "DENIED";
    }
}
