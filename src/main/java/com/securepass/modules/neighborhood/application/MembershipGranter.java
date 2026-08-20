package com.securepass.modules.neighborhood.application;

import com.securepass.modules.identity.domain.Role;

import java.util.UUID;

public interface MembershipGranter {

    void grant(UUID userId, UUID neighborhoodId, Role role);
}
