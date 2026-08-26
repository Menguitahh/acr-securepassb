package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class CreateLotUseCase {

    private final LotRepository lots;

    public CreateLotUseCase(LotRepository lots) {
        this.lots = lots;
    }

    public Lot create(UUID neighborhoodId, Command cmd) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        lots.findByNeighborhoodAndCode(neighborhoodId, cmd.code()).ifPresent(existing -> {
            throw new StateConflictException("ya existe un lote con code " + cmd.code());
        });

        Instant now = Instant.now();
        Lot lot = new Lot(
                UUID.randomUUID(), neighborhoodId, cmd.code(), cmd.street(), cmd.number(),
                LotStatus.ACTIVE, now, now);
        lots.save(lot);
        return lot;
    }

    public record Command(String code, String street, String number) {}
}
