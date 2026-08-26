package com.securepass.modules.identity.application;

import java.util.UUID;

public interface ActiveInvitationRevoker {

    void revokeActiveByLotAndUser(UUID lotId, UUID userId, UUID revokedBy);
}
