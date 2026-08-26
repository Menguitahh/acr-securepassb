package com.securepass.modules.invitation.application;

import java.util.Optional;
import java.util.UUID;

public interface FamilyMemberQuery {

    Optional<FamilyMembership> find(UUID userId, UUID lotId);
}
