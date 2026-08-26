package com.securepass.modules.invitation.application;

import java.util.Optional;
import java.util.UUID;

public interface NeighborhoodExtensionPolicyQuery {

    Optional<NeighborhoodExtensionPolicy> find(UUID neighborhoodId);
}
