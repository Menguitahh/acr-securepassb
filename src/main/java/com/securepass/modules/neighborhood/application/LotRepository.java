package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.Lot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LotRepository {

    Optional<Lot> findById(UUID id);

    Optional<Lot> findByNeighborhoodAndCode(UUID neighborhoodId, String code);

    List<Lot> listByNeighborhood(UUID neighborhoodId, int page, int size);

    /**
     * Autocomplete de lotes ACTIVE por prefijo de `code`. Case-insensitive,
     * ordenado por `code`, con tope de filas para uso en garita.
     */
    List<Lot> searchActiveByCodePrefix(UUID neighborhoodId, String prefix, int limit);

    void save(Lot lot);

    void update(Lot lot);
}
