package com.securepass.modules.neighborhood.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface NeighborhoodJpaRepository extends JpaRepository<NeighborhoodEntity, UUID> {

    List<NeighborhoodEntity> findAllByOrderByNameAsc();
}
