package com.securepass.modules.invitation.application;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;

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

class ExtendInvitationUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private InvitationRepository invitations;
    private NeighborhoodExtensionPolicyQuery policies;
    private FamilyMemberQuery family;
    private StayRepository stays;
    private ExtendInvitationUseCase useCase;

    private UUID actorUserId;
    private UUID creatorUserId;
    private UUID invitationId;
    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationRepository.class);
        policies = mock(NeighborhoodExtensionPolicyQuery.class);
        family = mock(FamilyMemberQuery.class);
        stays = mock(StayRepository.class);
        useCase = new ExtendInvitationUseCase(invitations, policies, family, stays);
        actorUserId = UUID.randomUUID();
        creatorUserId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
    }

    private Invitation invitation(UUID createdBy, InvitationStatus status) {
        return new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, status,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)),
                null, null, null, 1, 0, "kid-1", UUID.randomUUID(), false, NOW);
    }

    private Invitation extended(Instant newValidUntil) {
        return new Invitation(
                invitationId, neighborhoodId, lotId, creatorUserId,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), newValidUntil,
                null, null, null, 1, 0, "kid-1", UUID.randomUUID(), false, NOW);
    }

    // BR-16 happy path: creador extiende, se suma extension_minutes y se
    // actualiza expected_until de la stay abierta.
    @Test
    void br16CreadorExtiendeYActualizaStay() {
        Invitation inv = invitation(actorUserId, InvitationStatus.ACTIVE);
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(policies.find(neighborhoodId)).thenReturn(Optional.of(new NeighborhoodExtensionPolicy(2, 120)));
        when(invitations.findExtensionCount(invitationId)).thenReturn(0);
        Instant expectedNew = inv.validUntil().plus(Duration.ofMinutes(120));
        when(invitations.applyExtension(invitationId, expectedNew, 1)).thenReturn(extended(expectedNew));

        ExtendInvitationUseCase.Result result = useCase.extend(actorUserId, invitationId);

        assertThat(result.extensionCount()).isEqualTo(1);
        assertThat(result.maxExtensions()).isEqualTo(2);
        assertThat(result.invitation().validUntil()).isEqualTo(expectedNew);
        verify(stays).updateExpectedUntilByInvitation(invitationId, expectedNew);
    }

    // BR-16: al alcanzar el tope, rechaza.
    @Test
    void br16RechazaAlAlcanzarElTope() {
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(actorUserId, InvitationStatus.ACTIVE)));
        when(policies.find(neighborhoodId)).thenReturn(Optional.of(new NeighborhoodExtensionPolicy(2, 120)));
        when(invitations.findExtensionCount(invitationId)).thenReturn(2);

        assertThatThrownBy(() -> useCase.extend(actorUserId, invitationId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).rule()).isEqualTo("BR-16"));
        verify(invitations, never()).applyExtension(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(stays, never()).updateExpectedUntilByInvitation(any(), any());
    }

    // BR-17: no creador ni titular -> AccessDenied.
    @Test
    void br17RechazaSiNoEsCreadorNiTitular() {
        Invitation inv = invitation(creatorUserId, InvitationStatus.ACTIVE);
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(family.find(actorUserId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(actorUserId, lotId, false, true, true)));

        assertThatThrownBy(() -> useCase.extend(actorUserId, invitationId))
                .isInstanceOf(AccessDeniedException.class);
        verify(invitations, never()).applyExtension(any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    // BR-17: titular del lote (aunque no sea creador) puede extender.
    @Test
    void br17TitularDelLotePuedeExtender() {
        Invitation inv = invitation(creatorUserId, InvitationStatus.ACTIVE);
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(family.find(actorUserId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(actorUserId, lotId, true, true, true)));
        when(policies.find(neighborhoodId)).thenReturn(Optional.of(new NeighborhoodExtensionPolicy(2, 120)));
        when(invitations.findExtensionCount(invitationId)).thenReturn(0);
        Instant expectedNew = inv.validUntil().plus(Duration.ofMinutes(120));
        when(invitations.applyExtension(eq(invitationId), eq(expectedNew), eq(1))).thenReturn(extended(expectedNew));

        useCase.extend(actorUserId, invitationId);

        verify(invitations).applyExtension(invitationId, expectedNew, 1);
    }

    @Test
    void invitationInexistenteEs404() {
        when(invitations.findById(invitationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.extend(actorUserId, invitationId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void solaAcceptaActive() {
        when(invitations.findById(invitationId)).thenReturn(Optional.of(invitation(actorUserId, InvitationStatus.REVOKED)));

        assertThatThrownBy(() -> useCase.extend(actorUserId, invitationId))
                .isInstanceOf(BusinessRuleViolationException.class);
    }
}
