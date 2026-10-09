package com.securepass.modules.notification.application;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.access.domain.Stay;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * US-41: el guardia dispara un aviso push al residente que creo la
 * invitacion. Persiste `stays.resident_notified_at` para dejar el envio
 * registrado; el push real depende del adapter de `PushSender`.
 */
@Service
public class NotifyResidentOfStayUseCase {

    // Protege al residente, no al servidor: sin este limite el guardia puede
    // spamear veinte push seguidos por la misma visita y la gente silencia
    // la app entera. El limite global de WBS-9 no alcanza aca.
    static final Duration MIN_INTERVAL = Duration.ofMinutes(10);

    private final StayRepository stays;
    private final InvitationRepository invitations;
    private final PushSender push;

    public NotifyResidentOfStayUseCase(
            StayRepository stays,
            InvitationRepository invitations,
            PushSender push) {
        this.stays = stays;
        this.invitations = invitations;
        this.push = push;
    }

    @Transactional
    public Result notify(UUID neighborhoodId, UUID stayId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (stayId == null) throw new IllegalArgumentException("stayId requerido");

        Stay stay = stays.findById(stayId)
                .orElseThrow(() -> new ResourceNotFoundException("stay", stayId));
        if (!stay.neighborhoodId().equals(neighborhoodId)) {
            throw new ResourceNotFoundException("stay", stayId);
        }
        if (!stay.isOpen()) {
            throw new StateConflictException("stay ya cerrada");
        }
        if (stay.invitationId() == null) {
            // Stays sin invitacion (MANUAL, WBS-26) no tienen residente al que avisar.
            throw new StateConflictException("stay sin invitacion asociada");
        }

        Instant now = Instant.now();
        Optional<Instant> lastNotified = stays.findResidentNotifiedAt(stayId);
        if (lastNotified.isPresent()) {
            Duration since = Duration.between(lastNotified.get(), now);
            if (since.compareTo(MIN_INTERVAL) < 0) {
                throw new RateLimitExceededException(MIN_INTERVAL.minus(since));
            }
        }

        Invitation inv = invitations.findById(stay.invitationId())
                .orElseThrow(() -> new ResourceNotFoundException("invitation", stay.invitationId()));

        long minutesRemaining = Math.max(0, Duration.between(now, stay.expectedUntil()).toMinutes());
        push.notifyStayWarning(inv.createdBy(), new PushSender.StayWarning(
                stay.id(), inv.id(), stay.subjectName(), stay.enteredAt(), minutesRemaining));

        stays.markResidentNotified(stayId, now);
        return new Result(stayId, now, minutesRemaining);
    }

    public record Result(UUID stayId, Instant notifiedAt, long minutesRemaining) {}
}
