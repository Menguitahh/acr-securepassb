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
    UNKNOWN
}
