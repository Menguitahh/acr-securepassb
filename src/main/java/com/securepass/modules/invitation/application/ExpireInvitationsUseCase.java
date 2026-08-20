package com.securepass.modules.invitation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ExpireInvitationsUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(ExpireInvitationsUseCase.class);

    private final InvitationRepository invitations;

    public ExpireInvitationsUseCase(InvitationRepository invitations) {
        this.invitations = invitations;
    }

    public int run() {
        Instant cutoff = Instant.now();
        int updated = invitations.expireOverdue(cutoff);
        if (updated > 0) {
            LOG.info("invitation.expiry.materialized cutoff={} rows={}", cutoff, updated);
        }
        return updated;
    }
}
