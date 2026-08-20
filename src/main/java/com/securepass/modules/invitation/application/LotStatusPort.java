package com.securepass.modules.invitation.application;

import java.util.Optional;
import java.util.UUID;

public interface LotStatusPort {

    Optional<String> find(UUID lotId);
}
