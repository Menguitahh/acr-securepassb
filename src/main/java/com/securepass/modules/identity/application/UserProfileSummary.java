package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;

import java.util.List;
import java.util.UUID;

public record UserProfileSummary(
        UUID neighborhoodId,
        String neighborhoodTimezone,
        List<Role> roles,
        List<LotSummary> lots) {
    public UserProfileSummary {
        roles = roles == null ? List.of() : List.copyOf(roles);
        lots = lots == null ? List.of() : List.copyOf(lots);
    }
}
