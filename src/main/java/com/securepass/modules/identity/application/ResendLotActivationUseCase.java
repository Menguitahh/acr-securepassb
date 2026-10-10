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
 * US-54: el titular reenvia la activacion a un familiar o trabajador de su
 * lote que todavia no activo la cuenta. Va al mismo email (no lo cambia: eso
 * es #181), asi que no hay riesgo de quedarse con la cuenta de otro. El gate
 * de OWNER del lote lo aplica el controller.
 */
@AllArgsConstructor
@Service
public class ResendLotActivationUseCase {

    private final UserRepository users;
    private final IssueActivationTokenUseCase issuer;

    public void resend(UUID lotId, UUID userId, String actorFullName, String neighborhoodName) {
        boolean inLot = users.belongsToLotAsMemberOrWorker(userId, lotId);
        if (!inLot) throw new ResourceNotFoundException("persona del lote", userId);
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("persona del lote", userId));
        if (user.status() != UserStatus.PENDING_ACTIVATION) {
            throw new StateConflictException("la cuenta ya esta activa");
        }
        if (user.email() == null || user.email().isBlank()) {
            throw new StateConflictException("la persona no tiene email: cargalo primero");
        }
        issuer.issue(userId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
    }
}
