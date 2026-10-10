package com.securepass.modules.neighborhood.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** securepassb#258: un barrio en el panel de la plataforma. */
public record NeighborhoodOverview(UUID id, String name, String slug, String timezone, Instant createdAt,
                                   long lotCount, List<Admin> admins) {

    /** email null si el admin se dio de alta sin email (no deberia, pero la columna lo admite). */
    public record Admin(String fullName, String email, String status) {}
}
