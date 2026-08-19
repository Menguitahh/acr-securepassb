package com.securepass.modules.access.application;

import java.time.Duration;
import java.util.UUID;

public interface AntiReplayStore {

    ClaimResult claim(UUID jti, int useSeq, String clientEventId, Duration ttl);

    enum ClaimResult {
        FRESH,
        IDEMPOTENT,
        REPLAY
    }
}
