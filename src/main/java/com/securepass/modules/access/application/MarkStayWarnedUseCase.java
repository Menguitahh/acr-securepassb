package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.Stay;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * WBS-69 (BR-15): la garita reporta que ya avisó localmente sobre una
 * stay. Persistir `warned_at` en el server evita que el guardia entrante
 * del proximo turno vuelva a disparar el mismo aviso.
 *
 * Idempotente: llamadas repetidas devuelven el `warned_at` original.
 */
@AllArgsConstructor
@Service
public class MarkStayWarnedUseCase {

    private final StayRepository stays;

    @Transactional
    public Result mark(UUID neighborhoodId, UUID stayId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (stayId == null) throw new IllegalArgumentException("stayId requerido");

        Stay stay = stays.findById(stayId)
                .orElseThrow(() -> new ResourceNotFoundException("stay", stayId));
        if (!stay.neighborhoodId().equals(neighborhoodId)) {
            throw new ResourceNotFoundException("stay", stayId);
        }
        if (!stay.isOpen()) {
            throw new StateConflictException("stay ya cerrada");
        }

        Instant warnedAt = stays.markWarned(stayId, Instant.now());
        return new Result(stayId, warnedAt);
    }

    public record Result(UUID stayId, Instant warnedAt) {}
}
