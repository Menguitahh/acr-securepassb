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

    public SyncAccessUseCase(RecordAccessAttemptUseCase recorder) {
        this.recorder = recorder;
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

        int accepted = 0;
        List<Conflict> conflicts = new ArrayList<>();
        for (Event ev : ordered) {
            RecordAccessAttemptUseCase.Result result = recorder.record(new RecordAccessAttemptUseCase.Command(
                    neighborhoodId, ev.gateId(), guardId, ev.token(), ev.direction(),
                    ev.companions(), ev.capturedAt(), ev.clientEventId(), true));

            AccessLog log = result.log();
            if (result.decision() instanceof AccessDecision.Allowed) {
                accepted++;
            } else {
                DenialReason reason = ((AccessDecision.Denied) result.decision()).reason();
                conflicts.add(new Conflict(ev.clientEventId(), reason, log.id(), log.clockSkew()));
            }
        }
        return new Report(accepted, conflicts);
    }

    public record Event(
            UUID clientEventId,
            String token,
            UUID gateId,
            Direction direction,
            int companions,
            Instant capturedAt,
            String localDecision
    ) {
        public Event {
            if (clientEventId == null) throw new IllegalArgumentException("clientEventId requerido");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("token requerido");
            if (gateId == null) throw new IllegalArgumentException("gateId requerido");
            if (direction == null) throw new IllegalArgumentException("direction requerido");
            if (companions < 0) throw new IllegalArgumentException("companions >= 0");
            if (capturedAt == null) throw new IllegalArgumentException("capturedAt requerido");
        }
    }

    public record Conflict(UUID clientEventId, DenialReason reason, UUID logId, boolean clockSkew) {}

    public record Report(int accepted, List<Conflict> conflicts) {}
}
