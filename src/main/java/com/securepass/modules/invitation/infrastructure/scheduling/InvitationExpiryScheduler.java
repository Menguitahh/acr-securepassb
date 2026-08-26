package com.securepass.modules.invitation.infrastructure.scheduling;

import com.securepass.modules.invitation.application.ExpireInvitationsUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InvitationExpiryScheduler {

    private final ExpireInvitationsUseCase useCase;

    public InvitationExpiryScheduler(ExpireInvitationsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "${securepass.invitation-expiry-cron:0 */15 * * * *}")
    public void expire() {
        useCase.run();
    }
}
