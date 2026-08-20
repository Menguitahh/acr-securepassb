package com.securepass.modules.invitation.application;

import java.util.List;
import java.util.UUID;

public interface ResidentLotsQuery {

    List<ResidentLot> listActiveByUser(UUID userId, UUID neighborhoodId);
}
