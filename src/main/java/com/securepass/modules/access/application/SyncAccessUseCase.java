package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class SyncAccessUseCase {

    public static final int MAX_BATCH = 50;

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
        RecordAccessAttemptUseCase.Result result = recorder.record(new RecordAccessAttemptUseCase.Command(
                neighborhoodId, ev.gateId(), guardId, ev.token(), ev.direction(),
                ev.companions(), ev.capturedAt(), ev.clientEventId(), true));
        AccessLog log = result.log();
        if (result.decision() instanceof AccessDecision.Allowed) {
            return new EventResult(
                    ev.clientEventId(), Outcome.ALLOWED, null, log.id(), null, log.clockSkew());
        }
        DenialReason reason = ((AccessDecision.Denied) result.decision()).reason();
        return new EventResult(
                ev.clientEventId(), Outcome.DENIED, reason, log.id(), null, log.clockSkew());
    }

    private EventResult processEnroll(UUID neighborhoodId, UUID guardId, Event ev) {
        EnrollmentResult result = enroller.enroll(new EnrollGuestUseCase.Command(
                neighborhoodId, ev.gateId(), guardId, ev.token(), ev.documentNumber(),
                ev.clientEventId(), ev.capturedAt(), true));
        AccessLog log = result.log();
        if (result instanceof EnrollmentResult.Enrolled ok) {
            return new EventResult(
                    ev.clientEventId(), Outcome.ENROLLED, null, log.id(),
                    ok.enrollment().id(), log.clockSkew());
        }
        DenialReason reason = ((EnrollmentResult.Denied) result).reason();
        return new EventResult(
                ev.clientEventId(), Outcome.DENIED, reason, log.id(), null, log.clockSkew());
    }

    public record Event(
            UUID clientEventId,
            String token,
            UUID gateId,
            Direction direction,
            int companions,
            Instant capturedAt,
            String localDecision,
            String documentNumber
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

    public enum Outcome { ALLOWED, DENIED, ENROLLED }

    public record EventResult(
            UUID clientEventId,
            Outcome result,
            DenialReason reason,
            UUID logId,
            UUID enrollmentId,
            boolean clockSkew
    ) {}

    public record Report(List<EventResult> results) {}
}
