package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.InvitationPage;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

public record PagedInvitationsResponse(List<InvitationResponse> items, int page, int size, long total) {

    public static PagedInvitationsResponse from(InvitationPage page, Function<UUID, String> neighborhoodName) {
        List<InvitationResponse> items = page.items().stream()
                .map(inv -> InvitationResponse.withoutQr(inv, neighborhoodName.apply(inv.neighborhoodId())))
                .toList();
        return new PagedInvitationsResponse(items, page.page(), page.size(), page.total());
    }
}
