package com.securepass.modules.invitation.application;

import java.util.UUID;

public interface NeighborhoodNameQuery {

    String findName(UUID neighborhoodId);
}
