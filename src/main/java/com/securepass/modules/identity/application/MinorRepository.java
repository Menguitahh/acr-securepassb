package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Minor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MinorRepository {

    /**
     * Retorna el {@code neighborhood_id} del lote o vacio si el lote no
     * existe. Usado por el alta para denormalizar el barrio y por el
     * guardian cross-barrio del list/graduate.
     */
    Optional<UUID> findLotNeighborhood(UUID lotId);

    void save(Minor minor);

    Optional<Minor> findById(UUID id);

    List<Minor> findByLot(UUID lotId);

    /**
     * Hard delete. Los menores no dejan historial (no generan permisos ni
     * accesos), asi que soft delete seria peso sin uso.
     * Devuelve true si borro una fila; false si el id no existia.
     */
    boolean delete(UUID id);
}
