package com.securepass.modules.invitation.application;

import java.util.UUID;

public record ResidentPass(
        UUID lotId,
        String lotCode,
        String neighborhoodName,
        String qrToken,
        String kid
) {}
