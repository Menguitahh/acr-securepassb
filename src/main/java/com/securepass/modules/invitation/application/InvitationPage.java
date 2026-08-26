package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;

import java.util.List;

public record InvitationPage(List<Invitation> items, int page, int size, long total) {

    public InvitationPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
