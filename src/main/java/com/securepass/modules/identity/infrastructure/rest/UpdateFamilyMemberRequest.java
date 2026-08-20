package com.securepass.modules.identity.infrastructure.rest;

public record UpdateFamilyMemberRequest(
        Boolean canInvite,
        Boolean active
) {}
