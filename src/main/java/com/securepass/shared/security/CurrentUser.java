package com.securepass.shared.security;

import com.securepass.modules.identity.domain.Role;

import java.util.List;
import java.util.UUID;

public record CurrentUser(UUID userId, UUID neighborhoodId, List<Role> roles, List<UUID> lots) {

    public CurrentUser {
        if (userId == null) throw new IllegalArgumentException("userId requerido");
        roles = roles == null ? List.of() : List.copyOf(roles);
        lots = lots == null ? List.of() : List.copyOf(lots);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }
}
