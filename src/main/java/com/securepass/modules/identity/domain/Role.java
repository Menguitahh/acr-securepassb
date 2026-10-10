package com.securepass.modules.identity.domain;

public enum Role {
    ADMIN,
    GUARD,
    RESIDENT_OWNER,
    RESIDENT_MEMBER,
    // securepassb#258: duenio de la plataforma. Sale de platform_admins, no de
    // memberships (el CHECK de memberships no lo admite).
    PLATFORM_ADMIN;

    private static final String PREFIX = "ROLE_";

    /**
     * securepassb#256: valor en memberships.role y authority de Spring
     * Security (hasRole('ADMIN') busca ROLE_ADMIN). La API usa name().
     */
    public String authority() {
        return PREFIX + name();
    }

    public static Role fromAuthority(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("rol sin prefijo " + PREFIX + ": " + value);
        }
        return valueOf(value.substring(PREFIX.length()));
    }
}
