package com.securepass.modules.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * US-54: cuenta del barrio que todavia no se activo. roles: membresias del
 * barrio (RESIDENT_OWNER, GUARD...); lotCodes: lotes donde tiene permiso
 * (titular, familiar o trabajador).
 */
public record PendingActivation(UUID userId, String fullName, String email, List<String> roles,
                                List<String> lotCodes, Instant createdAt) {}
