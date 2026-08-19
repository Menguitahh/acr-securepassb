package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumeAccessUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private ValidateAccessUseCase validator;
    private InvitationRepository invitations;
    private AntiReplayStore antiReplay;
    private ConsumeAccessUseCase useCase;

    private UUID invitationId;
    private UUID neighborhoodId;
    private UUID lotId;
    private UUID createdBy;
    private UUID jti;

    @BeforeEach
    void setUp() {
        validator = mock(ValidateAccessUseCase.class);
        invitations = mock(InvitationRepository.class);
        antiReplay = mock(AntiReplayStore.class);
        useCase = new ConsumeAccessUseCase(validator, invitations, antiReplay);

        invitationId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        createdBy = UUID.randomUUID();
        jti = UUID.randomUUID();
    }

    private Invitation active(int maxUses, int usedCount) {
        Instant from = NOW.minus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        return new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                maxUses == 1 ? InvitationType.SINGLE : InvitationType.EVENT,
                InvitationStatus.ACTIVE,
                "Maria", null, 2, null,
                from, until, null, null, null,
                maxUses, usedCount, "kid-1", jti, false, NOW);
    }

    private AccessDecision.Allowed allowed() {
        return new AccessDecision.Allowed(invitationId, jti, "Maria", "M4-L12", 2);
    }

    @Test
    void validacionDeniedNoConsumeNiClamea() {
        when(validator.validate("t", NOW)).thenReturn(AccessDecision.Denied.of(DenialReason.EXPIRED));

        AccessDecision result = useCase.consume("t", NOW, "evt-1");

        assertThat(result).isEqualTo(AccessDecision.Denied.of(DenialReason.EXPIRED));
        verify(invitations, never()).findByIdForUpdate(any());
        verify(antiReplay, never()).claim(any(), anyInt(), any(), any());
    }

    private static int anyInt() { return org.mockito.ArgumentMatchers.anyInt(); }

    @Test
    void freshClaimIncrementaUsedCountYCommit() {
        Invitation inv = active(2, 0);
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        when(antiReplay.claim(eq(jti), eq(1), eq("evt-1"), any())).thenReturn(AntiReplayStore.ClaimResult.FRESH);

        AccessDecision result = useCase.consume("t", NOW, "evt-1");

        assertThat(result).isInstanceOf(AccessDecision.Allowed.class);
        ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).persistConsumption(captor.capture());
        assertThat(captor.getValue().usedCount()).isEqualTo(1);
    }

    @Test
    void freshClaimAlLimiteMarcaComoUsed() {
        Invitation inv = active(1, 0);
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        when(antiReplay.claim(eq(jti), eq(1), eq("evt-1"), any())).thenReturn(AntiReplayStore.ClaimResult.FRESH);

        useCase.consume("t", NOW, "evt-1");

        ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).persistConsumption(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(InvitationStatus.USED);
    }

    @Test
    void idempotentDevuelveAllowedSinIncrementar() {
        Invitation inv = active(2, 0);
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        when(antiReplay.claim(any(), eq(1), eq("evt-1"), any()))
                .thenReturn(AntiReplayStore.ClaimResult.IDEMPOTENT);

        AccessDecision result = useCase.consume("t", NOW, "evt-1");

        assertThat(result).isInstanceOf(AccessDecision.Allowed.class);
        verify(invitations, never()).persistConsumption(any());
    }

    @Test
    void replayDevuelveDenied() {
        Invitation inv = active(2, 0);
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));
        when(antiReplay.claim(any(), eq(1), any(), any()))
                .thenReturn(AntiReplayStore.ClaimResult.REPLAY);

        AccessDecision result = useCase.consume("t", NOW, "evt-otra");

        assertThat(result).isEqualTo(AccessDecision.Denied.of(DenialReason.REPLAY));
        verify(invitations, never()).persistConsumption(any());
    }

    @Test
    void invitacionYaConsumidaBajoLockDevuelveAlreadyUsed() {
        // Otro proceso ya consumio entre validate() y findByIdForUpdate()
        Invitation inv = active(1, 1);
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));

        AccessDecision result = useCase.consume("t", NOW, "evt-1");

        assertThat(result).isEqualTo(AccessDecision.Denied.of(DenialReason.ALREADY_USED));
        verify(antiReplay, never()).claim(any(), anyInt(), any(), any());
    }

    @Test
    void revokedBajoLockDevuelveRevoked() {
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.REVOKED,
                "X", null, 0, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)),
                null, null, null, 1, 0, "kid-1", jti, false, NOW);
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.of(inv));

        AccessDecision result = useCase.consume("t", NOW, "evt-1");

        assertThat(result).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED));
    }

    @Test
    void invitacionDesaparecidaEsUnknown() {
        when(validator.validate("t", NOW)).thenReturn(allowed());
        when(invitations.findByIdForUpdate(invitationId)).thenReturn(Optional.empty());

        AccessDecision result = useCase.consume("t", NOW, "evt-1");

        assertThat(result).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN));
    }

    @Test
    void clientEventIdVacioLanza() {
        assertThatThrownBy(() -> useCase.consume("t", NOW, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
