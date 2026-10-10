package com.securepass.modules.identity.domain;

public enum Role {
    ADMIN,
    GUARD,
    RESIDENT_OWNER,
    RESIDENT_MEMBER;

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
