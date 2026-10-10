package com.securepass.modules.invitation.infrastructure.scheduling;

import com.securepass.modules.invitation.application.ExpireInvitationsUseCase;
import lombok.AllArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@AllArgsConstructor
@Component
public class InvitationExpiryScheduler {

    private final ExpireInvitationsUseCase useCase;

    @Scheduled(cron = "${securepass.invitation-expiry-cron:0 */15 * * * *}")
    public void expire() {
        useCase.run();
    }
}
