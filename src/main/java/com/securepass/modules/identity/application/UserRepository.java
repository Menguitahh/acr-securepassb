package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findByIdentifier(String identifier);

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(String email);

    /**
     * Fija passwordHash y pasa status a ACTIVE. Retorna true si actualizo
     * exactamente una fila; false si el usuario ya no existe o si su status
     * no admite activacion (por ejemplo, SUSPENDED).
     */
    boolean activateWithPassword(UUID userId, String passwordHash);

    /**
     * Recuperacion de contrasena (US-53): actualiza el hash sin tocar el
     * status. Solo aplica a usuarios ACTIVE; un SUSPENDED o DELETED no
     * puede resetear.
     */
    boolean updatePassword(UUID userId, String passwordHash);
}
