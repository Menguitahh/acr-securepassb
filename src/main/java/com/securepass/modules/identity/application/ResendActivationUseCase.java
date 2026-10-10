package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * WBS-128: reenvio del email de activacion desde la pantalla de admin.
 * Delegamos la invalidacion del token anterior al issuer (consumeAllActive).
 * Rate limit por usuario objetivo lo aplica el controller (misma capa que
 * los limites de auth/forgot-password).
 */
@AllArgsConstructor
@Service
public class ResendActivationUseCase {

    private final UserRepository users;
    private final IssueActivationTokenUseCase issuer;

    public void resend(UUID userId, String actorFullName, String neighborhoodName) {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        User user = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user", userId));
        if (user.status() != UserStatus.PENDING_ACTIVATION) {
            throw new StateConflictException("el usuario no esta en PENDING_ACTIVATION");
        }
        issuer.issue(userId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
    }
}
