package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.InvitationPage;

import java.util.List;

public record PagedInvitationsResponse(List<InvitationResponse> items, int page, int size, long total) {

    public static PagedInvitationsResponse from(InvitationPage page) {
        List<InvitationResponse> items = page.items().stream()
                .map(InvitationResponse::withoutQr)
                .toList();
        return new PagedInvitationsResponse(items, page.page(), page.size(), page.total());
    }
}
