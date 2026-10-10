package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * US-21: ingreso manual sin QR. Requiere turno abierto (BR-09), persiste
 * como `subject_type = MANUAL` y sin `invitation_id`. Idempotente por
 * `clientEventId`.
 */
@Service
public class ManualAccessUseCase {

    private final OpenShiftQuery shifts;
    private final LotStatusQuery lots;
    private final AccessLogRepository logs;

    public ManualAccessUseCase(OpenShiftQuery shifts, LotStatusQuery lots, AccessLogRepository logs) {
        this.shifts = shifts;
        this.lots = lots;
        this.logs = logs;
    }

    @Transactional
    public AccessLog record(Command cmd) {
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        Optional<AccessLog> existing = logs.findByClientEventId(cmd.clientEventId());
        if (existing.isPresent()) return existing.get();

        if (!shifts.hasOpenShift(cmd.guardId())) {
            throw new StateConflictException("el guardia no tiene turno abierto");
        }

        // Aislamiento por barrio (spec 5.5): el lote debe pertenecer al barrio del actor.
        UUID lotBarrio = lots.findNeighborhoodId(cmd.lotId())
                .orElseThrow(() -> new ResourceNotFoundException("lote", cmd.lotId()));
        if (!lotBarrio.equals(cmd.neighborhoodId())) {
            throw new ResourceNotFoundException("lote", cmd.lotId());
        }

        AccessLog log = new AccessLog(
                UUID.randomUUID(), cmd.neighborhoodId(), cmd.gateId(), cmd.guardId(),
                null, cmd.lotId(),
                Direction.IN, SubjectType.MANUAL, true, null,
                cmd.subjectName(), cmd.subjectDocument(), cmd.companions(),
                null, cmd.capturedAt(), false, false, cmd.clientEventId());
        return logs.saveIdempotent(log);
    }

    public record Command(
            UUID neighborhoodId,
            UUID gateId,
            UUID guardId,
            UUID lotId,
            String subjectName,
            String subjectDocument,
            UUID clientEventId,
            Instant capturedAt,
            int companions
    ) {
        public Command {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (gateId == null) throw new IllegalArgumentException("gateId requerido");
            if (guardId == null) throw new IllegalArgumentException("guardId requerido");
            if (lotId == null) throw new IllegalArgumentException("lotId requerido");
            if (subjectName == null || subjectName.isBlank()) {
                throw new IllegalArgumentException("subjectName requerido");
            }
            if (subjectDocument == null || subjectDocument.isBlank()) {
                throw new IllegalArgumentException("subjectDocument requerido");
            }
            if (clientEventId == null) throw new IllegalArgumentException("clientEventId requerido");
            if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
            if (companions < 0 || companions > 10) throw new IllegalArgumentException("companions 0..10");
        }
    }
}
