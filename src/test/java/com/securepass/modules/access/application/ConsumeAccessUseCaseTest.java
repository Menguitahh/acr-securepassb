package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #210: los denegados que nacen bajo el lock conservan el pase ya resuelto. */
class ConsumeAccessUseCaseTest {

    private static final Instant NOW = Instant.now();

    private InvitationRepository invitations;
    private AntiReplayStore antiReplay;
    private ConsumeAccessUseCase useCase;
    private UUID neighborhoodId;
    private UUID invitationId;
    private UUID jti;

    @BeforeEach
    void setUp() {
        ValidateAccessUseCase validator = mock(ValidateAccessUseCase.class);
        invitations = mock(InvitationRepository.class);
        antiReplay = mock(AntiReplayStore.class);
        useCase = new ConsumeAccessUseCase(validator, invitations, antiReplay);
        neighborhoodId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        jti = UUID.randomUUID();
        when(validator.validate(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new AccessDecision.Allowed(invitationId, jti, "Maria", "M4-L12", 0));
    }

    private void locked(InvitationStatus status, int usedCount) {
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(new Invitation(
                invitationId, neighborhoodId, UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.SINGLE, status, "Maria", "30123456", 0, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)),
                1, usedCount, "kid-1", jti, false, NOW)));
    }

    private AccessDecision consume() {
        return useCase.consume(neighborhoodId, "SP1.token", NOW, UUID.randomUUID().toString(), true);
    }

    @Test
    void revocadaBajoLockLlevaLaInvitacion() {
        locked(InvitationStatus.REVOKED, 0);

        assertThat(consume()).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED, invitationId));
    }

    @Test
    void cupoAgotadoBajoLockLlevaLaInvitacion() {
        locked(InvitationStatus.USED, 1);

        assertThat(consume()).isEqualTo(AccessDecision.Denied.of(DenialReason.ALREADY_USED, invitationId));
    }

    @Test
    void replayLlevaLaInvitacion() {
        locked(InvitationStatus.ACTIVE, 0);
        when(antiReplay.claim(any(), anyInt(), any(), any())).thenReturn(AntiReplayStore.ClaimResult.REPLAY);

        assertThat(consume()).isEqualTo(AccessDecision.Denied.of(DenialReason.REPLAY, invitationId));
    }

    @Test
    void invitacionQueDesaparecioBajoLockNoLlevaNada() {
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.empty());

        assertThat(consume()).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN));
    }
}
