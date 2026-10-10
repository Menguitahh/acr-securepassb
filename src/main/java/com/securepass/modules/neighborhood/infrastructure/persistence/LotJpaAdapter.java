package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.application.LotRepository;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.config.JpaConfig;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * securepassb#257: implementa el puerto {@link LotRepository} con JPA. Antes
 * era LotJdbcRepository; los casos de uso no cambian porque dependen del
 * puerto, no de la tecnologia.
 */
@AllArgsConstructor
@Repository
public class LotJpaAdapter implements LotRepository {

    private final LotJpaRepository jpa;

    @Override
    public Optional<Lot> findById(UUID id) {
        return jpa.findById(id).map(LotEntity::toDomain);
    }

    @Override
    public Optional<Lot> findByNeighborhoodAndCode(UUID neighborhoodId, String code) {
        return jpa.findByNeighborhoodIdAndCode(neighborhoodId, code).map(LotEntity::toDomain);
    }

    @Override
    public List<Lot> listByNeighborhood(UUID neighborhoodId, int page, int size) {
        return jpa.findByNeighborhoodIdOrderByCode(neighborhoodId, PageRequest.of(page, size)).stream()
                .map(LotEntity::toDomain).toList();
    }

    @Override
    public List<Lot> searchActiveByCodePrefix(UUID neighborhoodId, String prefix, int limit) {
        return jpa.findByNeighborhoodIdAndStatusAndCodeStartingWithIgnoreCaseOrderByCode(
                        neighborhoodId, LotStatus.ACTIVE, prefix, Limit.of(limit)).stream()
                .map(LotEntity::toDomain).toList();
    }

    // saveAndFlush: el UNIQUE (neighborhood_id, code) salta aca y no al cerrar
    // una transaccion de afuera.
    @Override
    @Transactional(JpaConfig.JPA_TX)
    public void save(Lot lot) {
        jpa.saveAndFlush(LotEntity.from(lot));
    }

    @Override
    @Transactional(JpaConfig.JPA_TX)
    public void update(Lot lot) {
        jpa.saveAndFlush(LotEntity.from(lot));
    }
}
