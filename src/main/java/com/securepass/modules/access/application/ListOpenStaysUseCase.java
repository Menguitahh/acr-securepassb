package com.securepass.modules.access.application;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@Service
public class ListOpenStaysUseCase {

    private final StayRepository stays;

    public List<OpenStayView> list(UUID neighborhoodId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return stays.listOpenByNeighborhood(neighborhoodId);
    }

    // Residente: solo las estadias de sus lotes (los del JWT). Alimenta la
    // card de permanencias por vencer (WBS-72).
    public List<OpenStayView> listForLots(UUID neighborhoodId, List<UUID> lotIds) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return stays.listOpenByLots(neighborhoodId, lotIds);
    }
}
