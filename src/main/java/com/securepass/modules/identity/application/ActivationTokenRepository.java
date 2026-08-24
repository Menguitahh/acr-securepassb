package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;

import java.util.Optional;
import java.util.UUID;

public interface ActivationTokenRepository {

    void save(ActivationToken token);

    Optional<ActivationToken> findByHash(byte[] tokenHash);

    /**
     * Marca el token como consumido si esta activo y no vencido. Retorna
     * true si esta llamada fue la que lo consumio; false si ya estaba
     * consumido, vencido o inexistente. La condicion se evalua a nivel
     * de UPDATE para cerrar la ventana de carrera entre dos requests.
     */
    boolean markConsumed(UUID tokenId);

    /**
     * Consume todos los tokens activos de un usuario para un proposito
     * dado. Usado en reemisiones (US-54): al enviar un nuevo enlace,
     * los anteriores dejan de servir.
     */
    int consumeAllActive(UUID userId, TokenPurpose purpose);
}
