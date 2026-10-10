package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.domain.LotStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** securepassb#257: Spring Data arma el SQL a partir del nombre de cada metodo. */
interface LotJpaRepository extends JpaRepository<LotEntity, UUID> {

    Optional<LotEntity> findByNeighborhoodIdAndCode(UUID neighborhoodId, String code);

    List<LotEntity> findByNeighborhoodIdOrderByCode(UUID neighborhoodId, Pageable page);

    // Autocomplete de garita: prefijo sin distinguir mayusculas. StartingWith
    // escapa % y _ del parametro.
    List<LotEntity> findByNeighborhoodIdAndStatusAndCodeStartingWithIgnoreCaseOrderByCode(
            UUID neighborhoodId, LotStatus status, String prefix, Limit limit);
}
