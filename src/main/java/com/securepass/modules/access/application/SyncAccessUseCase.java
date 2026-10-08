package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.shared.exception.InvalidLotChoiceException;
import com.securepass.shared.metrics.AccessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class SyncAccessUseCase {

    public static final int MAX_BATCH = 50;

    private static final Logger LOG = LoggerFactory.getLogger(SyncAccessUseCase.class);

    private final RecordAccessAttemptUseCase recorder;
    private final EnrollGuestUseCase enroller;
    private final AccessLogRepository logs;
    private final AccessMetrics metrics;

    public SyncAccessUseCase(RecordAccessAttemptUseCase recorder, EnrollGuestUseCase enroller,
                             AccessLogRepository logs, AccessMetrics metrics) {
        this.recorder = recorder;
        this.enroller = enroller;
        this.logs = logs;
        this.metrics = metrics;
    }

    public Report sync(UUID neighborhoodId, UUID guardId, List<Event> events) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (guardId == null) throw new IllegalArgumentException("guardId requerido");
        if (events == null || events.isEmpty()) throw new IllegalArgumentException("events requerido");
        if (events.size() > MAX_BATCH) {
            throw new IllegalArgumentException("batch excede " + MAX_BATCH + " eventos");
        }

        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparing(Event::capturedAt));

        List<EventResult> results = new ArrayList<>(ordered.size());
        for (Event ev : ordered) {
            results.add(process(neighborhoodId, guardId, ev));
        }
        return new Report(results);
    }

    private EventResult process(UUID neighborhoodId, UUID guardId, Event ev) {
        // #217: un evento mal armado sale REJECTED sin trabar el batch, asi
        // que la decision local se parsea aca y no en el DTO.
        LocalDecision decision = parse(LocalDecision.class, ev.localDecision());
        DenialReason localReason = parse(DenialReason.class, ev.localDenialReason());
        if (decision == null) return reject(guardId, ev, Rejection.INVALID_LOCAL_DECISION);
        if (ev.localDenialReason() != null && localReason == null) {
            return reject(guardId, ev, Rejection.INVALID_LOCAL_DECISION);
        }
        if ((decision == LocalDecision.DENIED) != (localReason != null)) {
            return reject(guardId, ev, Rejection.INVALID_LOCAL_DECISION);
        }
        if (decision == LocalDecision.DENIED) return recordLocalDenied(neighborhoodId, guardId, ev, localReason);
        if (ev.documentNumber() != null) return processEnroll(neighborhoodId, guardId, ev);
        return processAccess(neighborhoodId, guardId, ev);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null) return null;
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Spec 8.4: el sync nunca sube un DENIED local a ALLOWED. El guardia no
     * dejo pasar a nadie: es un hecho, no algo a evaluar. Se guarda con el
     * motivo de la garita, sin re-decidir, sin consumir el pase ni enrolar.
     */
    // ponytail: sin vinculo al pase (invitation, jti, sujeto); vincularlo como
    // #210 pide verificar la firma, agregar si el admin necesita saber de quien
    // fue el rechazo offline.
    private EventResult recordLocalDenied(UUID neighborhoodId, UUID guardId, Event ev, DenialReason reason) {
        boolean clockSkew = Duration.between(ev.capturedAt(), Instant.now()).abs()
                .compareTo(RecordAccessAttemptUseCase.CLOCK_SKEW_THRESHOLD) > 0;
        AccessLog toSave = new AccessLog(
                UUID.randomUUID(), neighborhoodId, ev.gateId(), guardId,
                null, null, ev.direction(), SubjectType.GUEST,
                false, reason, null, ev.documentNumber(), ev.companions(),
                null, ev.capturedAt(), true, clockSkew, ev.clientEventId());
        AccessLog log = logs.saveIdempotent(toSave);
        if (log.id().equals(toSave.id())) metrics.recordResult(reason.name(), "sync");
        // Un reintento del mismo clientEventId devuelve lo que quedo guardado.
        if (log.allowed()) {
            return new EventResult(ev.clientEventId(), Outcome.ALLOWED, null, log.id(), null, log.clockSkew(), null);
        }
        return new EventResult(
                ev.clientEventId(), Outcome.DENIED, log.denialReason(), log.id(), null, log.clockSkew(), null);
    }

    private EventResult processAccess(UUID neighborhoodId, UUID guardId, Event ev) {
        RecordAccessAttemptUseCase.Result result;
        try {
            result = recorder.record(new RecordAccessAttemptUseCase.Command(
                    neighborhoodId, ev.gateId(), guardId, ev.token(), ev.direction(),
                    ev.companions(), ev.capturedAt(), ev.clientEventId(), true, ev.lotId()));
        } catch (InvalidLotChoiceException e) {
            return reject(guardId, ev, Rejection.INVALID_LOT);
        }
        AccessLog log = result.log();
        // WBS-117 / #217: el trabajador permitido tambien es ALLOWED; antes caia
        // en el cast a Denied (ClassCastException).
        if (result.decision() instanceof AccessDecision.Allowed
                || result.decision() instanceof AccessDecision.AllowedWorker) {
            return new EventResult(
                    ev.clientEventId(), Outcome.ALLOWED, null, log.id(), null, log.clockSkew(), null);
        }
        DenialReason reason = ((AccessDecision.Denied) result.decision()).reason();
        return new EventResult(
                ev.clientEventId(), Outcome.DENIED, reason, log.id(), null, log.clockSkew(), null);
    }

    private EventResult processEnroll(UUID neighborhoodId, UUID guardId, Event ev) {
        EnrollmentResult result;
        try {
            result = enroller.enroll(new EnrollGuestUseCase.Command(
                    neighborhoodId, ev.gateId(), guardId, ev.token(), ev.documentNumber(),
                    ev.clientEventId(), ev.capturedAt(), true, ev.lotId()));
        } catch (InvalidLotChoiceException e) {
            return reject(guardId, ev, Rejection.INVALID_LOT); // #219: misma regla que el acceso
        }
        AccessLog log = result.log();
        if (result instanceof EnrollmentResult.Enrolled ok) {
            return new EventResult(
                    ev.clientEventId(), Outcome.ENROLLED, null, log.id(),
                    ok.enrollment().id(), log.clockSkew(), null);
        }
        DenialReason reason = ((EnrollmentResult.Denied) result).reason();
        return new EventResult(
                ev.clientEventId(), Outcome.DENIED, reason, log.id(), null, log.clockSkew(), null);
    }

    // #217: evento mal formado, no decision de acceso. Terminal para este
    // evento sin trabar el batch. No se guarda como acceso, pero el ingreso
    // fisico pudo ocurrir: queda rastro.
    // ponytail: rastro en el log de la app; tabla propia cuando aparezca la
    // senal de #222.
    private static EventResult reject(UUID guardId, Event ev, Rejection rejection) {
        LOG.warn("sync: evento rechazado clientEventId={} guardId={} gateId={} lotId={} capturedAt={} "
                        + "localDecision={} localDenialReason={} motivo={}",
                ev.clientEventId(), guardId, ev.gateId(), ev.lotId(), ev.capturedAt(),
                ev.localDecision(), ev.localDenialReason(), rejection);
        return EventResult.rejected(ev.clientEventId(), rejection);
    }

    public record Event(
            UUID clientEventId,
            String token,
            UUID gateId,
            Direction direction,
            int companions,
            Instant capturedAt,
            // Crudos del cable: los valida process() y uno invalido es REJECTED
            // de este evento. localDecision: ALLOWED | DENIED. localDenialReason:
            // un DenialReason, obligatorio si y solo si localDecision es DENIED.
            String localDecision,
            String localDenialReason,
            String documentNumber,
            // #217: lote que eligio el guardia offline, o null.
            UUID lotId
    ) {
        public Event {
            if (clientEventId == null) throw new IllegalArgumentException("clientEventId requerido");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token requerido");
            if (gateId == null) throw new IllegalArgumentException("gateId requerido");
            if (direction == null) throw new IllegalArgumentException("direction requerido");
            if (companions < 0) throw new IllegalArgumentException("companions >= 0");
            if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
            if (documentNumber != null && documentNumber.isBlank()) {
                throw new IllegalArgumentException("documentNumber vacio");
            }
        }
    }

    public enum LocalDecision { ALLOWED, DENIED }

    /**
     * DENIED es una decision de acceso (lleva {@link DenialReason} y log);
     * REJECTED es un evento mal formado (lleva {@link Rejection}, sin log).
     * Los dos son terminales: el mobile saca el evento de la cola.
     */
    public enum Outcome { ALLOWED, DENIED, ENROLLED, REJECTED }

    public enum Rejection { INVALID_LOT, INVALID_LOCAL_DECISION }

    public record EventResult(
            UUID clientEventId,
            Outcome result,
            DenialReason reason,
            UUID logId,
            UUID enrollmentId,
            boolean clockSkew,
            Rejection rejection
    ) {
        static EventResult rejected(UUID clientEventId, Rejection rejection) {
            return new EventResult(clientEventId, Outcome.REJECTED, null, null, null, false, rejection);
        }
    }

    public record Report(List<EventResult> results) {}
}
