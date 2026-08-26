package com.securepass.modules.invitation.application;

import java.util.List;
import java.util.UUID;

public interface SigningKeyQuery {
    List<PublicKeyView> findActivePublicKeys(UUID neighborhoodId);
}
