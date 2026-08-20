package com.securepass.modules.access.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ListOpenStaysUseCase {

    private final StayRepository stays;

    public ListOpenStaysUseCase(StayRepository stays) {
        this.stays = stays;
    }

    public List<OpenStayView> list(UUID neighborhoodId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return stays.listOpenByNeighborhood(neighborhoodId);
    }
}
