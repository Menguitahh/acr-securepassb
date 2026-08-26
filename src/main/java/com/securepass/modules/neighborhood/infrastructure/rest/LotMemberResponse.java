package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.domain.LotMember;

import java.time.Instant;
import java.util.UUID;

public record LotMemberResponse(
        UUID id,
        UUID lotId,
        UUID userId,
        String relation,
        boolean isOwner,
        boolean canInvite,
        boolean active,
        Instant createdAt
) {
    public static LotMemberResponse from(LotMember m) {
        return new LotMemberResponse(
                m.id(), m.lotId(), m.userId(), m.relation(),
                m.isOwner(), m.canInvite(), m.active(), m.createdAt());
    }
}
