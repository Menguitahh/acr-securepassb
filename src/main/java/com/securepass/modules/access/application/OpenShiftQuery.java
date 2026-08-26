package com.securepass.modules.access.application;

import java.util.UUID;

public interface OpenShiftQuery {

    boolean hasOpenShift(UUID guardId);
}
