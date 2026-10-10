package com.securepass.modules.identity.application;

import java.time.Instant;
import java.util.UUID;

/**
 * securepassb#121: quien suspendio a la persona, cuando y por que. Una
 * suspension anterior a #121 no tiene datos: los cuatro campos llegan null.
 */
public record Suspension(UUID neighborhoodId, String neighborhoodName, String reason, Instant suspendedAt) {}
