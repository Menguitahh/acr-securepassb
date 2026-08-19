package com.securepass.modules.access.application;

import java.util.Optional;
import java.util.UUID;

public interface LotStatusQuery {

    Optional<LotSnapshot> find(UUID lotId);
}
