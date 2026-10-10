package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.User;
import com.securepass.modules.invitation.application.WorkerTokenRepository;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * securepassb#121, opcion 3: la suspension es global (la persona no entra a
 * ningun barrio), pero guarda que barrio la puso y por que. Solo ese barrio
 * la levanta; los demas ven el motivo en el alta y pueden pedir la revision.
 */
@Service
public class SuspendUserUseCase {

    static final int REASON_MIN = 3;
    static final int REASON_MAX = 280;
    static final int MESSAGE_MAX = 500;

    private final UserRepository users;
    private final WorkerTokenRepository workerTokens;
    private final ActiveInvitationRevoker invitations;
    private final ApplicationEventPublisher events;

    public SuspendUserUseCase(UserRepository users, WorkerTokenRepository workerTokens,
                              ActiveInvitationRevoker invitations, ApplicationEventPublisher events) {
        this.users = users;
        this.workerTokens = workerTokens;
        this.invitations = invitations;
        this.events = events;
    }

    @Transactional
    public void suspend(CurrentUser admin, UUID userId, String reason) {
        String clean = reason == null ? "" : reason.trim();
        if (clean.length() < REASON_MIN || clean.length() > REASON_MAX) {
            throw new IllegalArgumentException("motivo de " + REASON_MIN + " a " + REASON_MAX + " caracteres");
        }
        if (admin.userId().equals(userId)) throw new IllegalArgumentException("no se puede suspender la propia cuenta");
        if (!users.belongsToNeighborhood(userId, admin.neighborhoodId())) {
            throw new ResourceNotFoundException("user", userId);
        }
        if (!users.suspend(userId, admin.neighborhoodId(), clean)) {
            throw new StateConflictException("la cuenta ya esta suspendida o dada de baja");
        }
        // El login ya rechaza a un SUSPENDED (y el refresh tambien). Esto corta
        // lo que no pasa por el login: el QR del trabajador y el pase de
        // residente. Los dos salen en /access/revoked para la garita offline.
        workerTokens.revokeActive(userId);
        invitations.revokeResidentPasses(userId, admin.userId());
    }

    @Transactional
    public void lift(CurrentUser admin, UUID userId) {
        if (users.liftSuspension(userId, admin.neighborhoodId())) return;
        Suspension s = users.findSuspension(userId)
                .orElseThrow(() -> new StateConflictException("la cuenta no esta suspendida"));
        if (s.neighborhoodId() != null && !s.neighborhoodId().equals(admin.neighborhoodId())) {
            throw new StateConflictException("la suspension la puso otro barrio: pedile la revision");
        }
        // Suspension sin barrio de una persona que no es de este barrio.
        throw new ResourceNotFoundException("user", userId);
    }

    public void requestReview(CurrentUser admin, String adminName, String adminEmail, String adminNeighborhood,
                              UUID userId, String message) {
        String clean = message == null ? "" : message.trim();
        if (clean.length() > MESSAGE_MAX) throw new IllegalArgumentException("mensaje de hasta " + MESSAGE_MAX);
        Suspension s = users.findSuspension(userId)
                .orElseThrow(() -> new ResourceNotFoundException("user", userId));
        if (s.neighborhoodId() == null) {
            throw new StateConflictException("la suspension no registra que barrio la puso");
        }
        if (s.neighborhoodId().equals(admin.neighborhoodId())) {
            throw new StateConflictException("la suspension es de tu barrio: levantala desde Padron");
        }
        List<String> emails = users.activeAdminEmails(s.neighborhoodId());
        if (emails.isEmpty()) throw new StateConflictException("el barrio que la puso no tiene admins activos");
        String personName = users.findById(userId).map(User::fullName).orElse("la persona");
        events.publishEvent(new SuspensionReviewRequestedEvent(
                userId, personName, emails, adminName, adminEmail, adminNeighborhood, clean));
    }
}
