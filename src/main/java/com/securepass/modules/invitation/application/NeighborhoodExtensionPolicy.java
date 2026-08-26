package com.securepass.modules.invitation.application;

public record NeighborhoodExtensionPolicy(int maxExtensions, int extensionMinutes) {

    public NeighborhoodExtensionPolicy {
        if (maxExtensions < 0) throw new IllegalArgumentException("maxExtensions >= 0");
        if (extensionMinutes <= 0) throw new IllegalArgumentException("extensionMinutes > 0");
    }
}
