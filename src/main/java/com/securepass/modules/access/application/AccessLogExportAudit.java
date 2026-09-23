package com.securepass.modules.access.application;

import java.util.UUID;

/** US-32: cada export de la bitacora deja una fila append-only con quien, cuando y que filtros. */
public interface AccessLogExportAudit {

    void record(UUID actorUserId, AccessLogQuery.ExportFilter filter);
}
