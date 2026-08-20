package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.Lot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ListLotsUseCase {

    private static final int PAGE_SIZE = 50;

    private final LotRepository lots;

    public ListLotsUseCase(LotRepository lots) {
        this.lots = lots;
    }

    public List<Lot> list(UUID neighborhoodId, int page) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        int p = Math.max(0, page);
        return lots.listByNeighborhood(neighborhoodId, p, PAGE_SIZE);
    }
}
