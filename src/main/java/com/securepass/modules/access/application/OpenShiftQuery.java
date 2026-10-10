package com.securepass.modules.access.application;

import java.util.UUID;

public interface OpenShiftQuery {

    boolean hasOpenShift(UUID guardId);

    /** US-72: el turno abierto del guardia, o vacio. */
    java.util.Optional<UUID> currentShiftId(UUID guardId);
}
