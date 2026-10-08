package com.securepass.modules.access.domain;

public enum DenialReason {
    INVALID_SIGNATURE,
    NOT_YET_VALID,
    EXPIRED,
    ALREADY_USED,
    REVOKED,
    OUT_OF_SCHEDULE,
    LOT_SUSPENDED,
    REPLAY,
    DOCUMENT_MISMATCH,
    NOT_ENROLLED,
    // Solo los produce la garita offline: el dispositivo no tenia los datos.
    WORKER_NOT_SYNCED,
    NEIGHBORHOOD_TZ_MISSING,
    UNKNOWN
}
