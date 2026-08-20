package com.securepass.modules.identity.infrastructure.rest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddFamilyMemberRequest(
        @NotBlank String identifier,
        boolean canInvite,
        @Size(max = 30) String relation
) {}
