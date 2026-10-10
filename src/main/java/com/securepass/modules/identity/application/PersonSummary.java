package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.UserStatus;

import java.util.List;
import java.util.UUID;

/** securepassb#121: persona del barrio en la busqueda del admin. suspension null si no esta suspendida. */
public record PersonSummary(UUID userId, String fullName, String documentNumber, String email, UserStatus status,
                            List<String> roles, List<String> lotCodes, Suspension suspension) {}
