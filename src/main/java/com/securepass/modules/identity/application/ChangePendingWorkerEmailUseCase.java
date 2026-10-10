package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.UserOnboardingConflictException;
import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * securepassb#181: el titular corrige el email de un trabajador que todavia
 * no activo su cuenta (typo en el alta) o se lo agrega si lo cargo sin email
 * (securepassf#119), y se reenvia la activacion al email nuevo.
 *
 * <p>Solo si el actor es OWNER de todos los lotes donde la persona tiene
 * algun permiso y la persona no es miembro de ningun barrio. Si no, otro
 * titular podria redirigir la activacion a su propio email y quedarse con
 * la cuenta (y el QR) de alguien que tambien trabaja en otra casa. Una
 * cuenta ya activa nunca se toca: el email es suyo.
 */
@AllArgsConstructor
@Service
public class ChangePendingWorkerEmailUseCase {

    private final UserRepository users;
    private final WorkerPermissionRepository workers;
    private final IssueActivationTokenUseCase activation;

    @Transactional
    public Result change(CurrentUser actor, UUID lotId, UUID userId, String rawEmail,
                         String actorFullName, String neighborhoodName) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (rawEmail == null || rawEmail.isBlank()) throw new IllegalArgumentException("email requerido");
        String email = rawEmail.trim().toLowerCase(Locale.ROOT);

        if (workers.find(userId, lotId).isEmpty()) throw new ResourceNotFoundException("trabajador", userId);
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("trabajador", userId));
        if (user.status() != UserStatus.PENDING_ACTIVATION) {
            throw new UserOnboardingConflictException("USER_ALREADY_ACTIVE", userId,
                    "El trabajador ya activo su cuenta: el email lo cambia el");
        }
        if (!users.onlyOwnedBy(userId, actor.userId())) {
            throw new UserOnboardingConflictException("EMAIL_NOT_EDITABLE", userId,
                    "El trabajador tiene permisos en lotes de otro titular: no se puede cambiar su email desde aca");
        }
        users.findByEmail(email).filter(other -> !other.id().equals(userId)).ifPresent(other -> {
            throw new UserOnboardingConflictException("EMAIL_TAKEN", other.id(), "El email ya esta registrado");
        });

        try {
            if (!users.replacePendingEmail(userId, email)) {
                throw new UserOnboardingConflictException("USER_ALREADY_ACTIVE", userId,
                        "El trabajador ya activo su cuenta: el email lo cambia el");
            }
        } catch (DataIntegrityViolationException race) {
            throw new UserOnboardingConflictException("EMAIL_TAKEN", userId, "El email ya esta registrado");
        }
        // Invalida el link anterior (consumeAllActive) y manda uno nuevo.
        activation.issue(userId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        return new Result(userId, email);
    }

    public record Result(UUID userId, String email) {}
}
