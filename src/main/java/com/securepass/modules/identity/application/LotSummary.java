package com.securepass.modules.identity.application;

import com.securepass.shared.schedule.ScheduleWindow;

import java.util.UUID;

/**
 * #179: {@code kind} sale de {@code permission_type}. Un WORKER trae servicio
 * y franja, y nunca invita ni es titular; OWNER y MEMBER no los traen.
 */
public record LotSummary(UUID id, String code, boolean canInvite, boolean isOwner, Kind kind,
                         String service, ScheduleWindow schedule) {

    public enum Kind { OWNER, MEMBER, WORKER }

    public LotSummary {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
        if (kind == null) throw new IllegalArgumentException("kind requerido");
        if (kind != Kind.WORKER && (service != null || schedule != null)) {
            throw new IllegalArgumentException("service y schedule solo aplican a WORKER");
        }
        if (kind == Kind.WORKER && (service == null || service.isBlank() || schedule == null)) {
            throw new IllegalArgumentException("WORKER requiere service y schedule");
        }
        if (kind == Kind.WORKER && (canInvite || isOwner)) {
            throw new IllegalArgumentException("WORKER no invita ni es titular");
        }
    }

    public static LotSummary resident(UUID id, String code, boolean canInvite, boolean isOwner) {
        return new LotSummary(id, code, canInvite, isOwner, isOwner ? Kind.OWNER : Kind.MEMBER, null, null);
    }

    public static LotSummary worker(UUID id, String code, String service, ScheduleWindow schedule) {
        return new LotSummary(id, code, false, false, Kind.WORKER, service, schedule);
    }
}
