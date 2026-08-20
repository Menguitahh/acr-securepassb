package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class UpdateLotUseCase {

    private final LotRepository lots;

    public UpdateLotUseCase(LotRepository lots) {
        this.lots = lots;
    }

    public Lot update(UUID neighborhoodId, UUID lotId, Patch patch) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (patch == null) throw new IllegalArgumentException("patch requerido");

        Lot lot = lots.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!lot.neighborhoodId().equals(neighborhoodId)) {
            throw new ResourceNotFoundException("lote", lotId);
        }

        Instant now = Instant.now();
        Lot next = lot;
        if (patch.status() != null) next = next.withStatus(patch.status(), now);
        if (patch.street() != null || patch.number() != null) {
            String street = patch.street() != null ? patch.street() : next.street();
            String number = patch.number() != null ? patch.number() : next.number();
            next = next.withAddress(street, number, now);
        }
        if (next != lot) lots.update(next);
        return next;
    }

    public record Patch(LotStatus status, String street, String number) {}
}
