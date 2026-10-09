package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {
    Optional<User> findByIdentifier(String identifier);

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(String email);

    Optional<User> findByDocument(String documentNumber);

    /**
     * INSERT del user. No hace preverificaciones: si email o documento chocan
     * con constraints unique, propaga la excepcion de acceso a datos para
     * que el caller la traduzca a conflicto de dominio (evita el race del
     * doble click entre findBy + INSERT).
     */
    void create(User user);

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

    /**
     * WBS-107 / US-56: si el user existente no tiene email (caso trabajador
     * o menor cargado sin cuenta), lo completa. UPDATE con guard
     * "email IS NULL": si otro proceso lo lleno en el interin, esta
     * operacion es no-op y retorna false. Nombre no se toca — la regla
     * de email de US-56 es la unica que corrige datos existentes.
     */
    boolean completeEmail(UUID userId, String email);

    /**
     * securepassb#181: reemplaza el email de un user PENDING_ACTIVATION.
     * false si ya no esta pendiente (activo en el interin).
     */
    boolean replacePendingEmail(UUID userId, String email);

    /**
     * securepassb#181: true si todos los permisos de lote del user (activos
     * o no) son de lotes donde {@code ownerId} es OWNER activo, y el user no
     * es miembro de ningun barrio. Es la condicion para que ese titular
     * pueda cambiarle el email sin afectar a otro titular.
     */
    boolean onlyOwnedBy(UUID userId, UUID ownerId);
}
