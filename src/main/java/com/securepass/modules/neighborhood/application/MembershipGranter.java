package com.securepass.modules.neighborhood.application;

import com.securepass.modules.identity.domain.Role;

import java.util.UUID;

public interface MembershipGranter {

    void grant(UUID userId, UUID neighborhoodId, Role role);

    /**
     * WBS-107: consulta si ya existe la membresia (activa o no) antes de
     * otorgar, para saber si el alta por dedupe cambia estado o no.
     */
    boolean exists(UUID userId, UUID neighborhoodId, Role role);
}
