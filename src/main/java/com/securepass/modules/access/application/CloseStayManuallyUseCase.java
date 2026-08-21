package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.Stay;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * US-39: cierre manual de una stay desde la garita. Se persiste un
 * `access_log` de egreso `MANUAL` (append-only respetado) y se cierra
 * la `stay` con `closed_manually = TRUE`.
 */
@Service
public class CloseStayManuallyUseCase {

    private final StayRepository stays;
    private final AccessLogRepository logs;

    public CloseStayManuallyUseCase(StayRepository stays, AccessLogRepository logs) {
        this.stays = stays;
        this.logs = logs;
    }

    @Transactional
    public Stay close(UUID neighborhoodId, UUID gateId, UUID guardId, UUID stayId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (gateId == null) throw new IllegalArgumentException("gateId requerido");
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");
        if (stayId == null) throw new IllegalArgumentException("stayId requerido");

        Stay stay = stays.findById(stayId)
                .orElseThrow(() -> new ResourceNotFoundException("stay", stayId));
        if (!stay.neighborhoodId().equals(neighborhoodId)) {
            throw new ResourceNotFoundException("stay", stayId);
        }
        if (!stay.isOpen()) {
            throw new StateConflictException("stay ya cerrada");
        }

        Instant now = Instant.now();
        AccessLog synthetic = logs.saveIdempotent(new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId,
                stay.invitationId(), stay.lotId(),
                Direction.OUT, SubjectType.MANUAL, true, null,
                stay.subjectName(), stay.subjectDocument(), 0,
                null, now, false, false, UUID.randomUUID()));

        Stay closed = stay.close(synthetic.id(), now, true, guardId);
        stays.close(closed);
        return closed;
    }
}
