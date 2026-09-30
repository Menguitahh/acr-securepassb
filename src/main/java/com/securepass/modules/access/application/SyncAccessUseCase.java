package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.shared.exception.InvalidLotChoiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public SyncAccessUseCase(RecordAccessAttemptUseCase recorder, EnrollGuestUseCase enroller) {
        this.recorder = recorder;
        this.enroller = enroller;
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
            results.add(ev.documentNumber() != null
                    ? processEnroll(neighborhoodId, guardId, ev)
                    : processAccess(neighborhoodId, guardId, ev));
        }
        return new Report(results);
    }

    private EventResult processAccess(UUID neighborhoodId, UUID guardId, Event ev) {
        RecordAccessAttemptUseCase.Result result;
        try {
            result = recorder.record(new RecordAccessAttemptUseCase.Command(
                    neighborhoodId, ev.gateId(), guardId, ev.token(), ev.direction(),
                    ev.companions(), ev.capturedAt(), ev.clientEventId(), true, ev.lotId()));
        } catch (InvalidLotChoiceException e) {
            return reject(guardId, ev);
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
            return reject(guardId, ev); // #219: misma regla que el acceso
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
    private static EventResult reject(UUID guardId, Event ev) {
        LOG.warn("sync: evento rechazado clientEventId={} guardId={} gateId={} lotId={} capturedAt={} motivo={}",
                ev.clientEventId(), guardId, ev.gateId(), ev.lotId(), ev.capturedAt(), Rejection.INVALID_LOT);
        return EventResult.rejected(ev.clientEventId(), Rejection.INVALID_LOT);
    }

    public record Event(
            UUID clientEventId,
            String token,
            UUID gateId,
            Direction direction,
            int companions,
            Instant capturedAt,
            String localDecision,
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

    /**
     * DENIED es una decision de acceso (lleva {@link DenialReason} y log);
     * REJECTED es un evento mal formado (lleva {@link Rejection}, sin log).
     * Los dos son terminales: el mobile saca el evento de la cola.
     */
    public enum Outcome { ALLOWED, DENIED, ENROLLED, REJECTED }

    public enum Rejection { INVALID_LOT }

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
