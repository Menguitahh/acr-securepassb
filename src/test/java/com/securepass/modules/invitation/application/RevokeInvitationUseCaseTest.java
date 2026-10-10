package com.securepass.modules.invitation.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RevokeInvitationUseCaseTest {

    private InvitationRepository invitations;
    private FamilyMemberQuery family;
    private RevokeInvitationUseCase useCase;
    private UUID neighborhoodId;
    private UUID lotId;
    private UUID creatorId;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationRepository.class);
        family = mock(FamilyMemberQuery.class);
        useCase = new RevokeInvitationUseCase(invitations, family);
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
    }

    private Invitation active() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        return new Invitation(
                UUID.randomUUID(), neighborhoodId, lotId, creatorId,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                now, now.plus(Duration.ofHours(6)),
                1, 0, "kid-1", UUID.randomUUID(), false, now);
    }

    private CurrentUser user(UUID id, Role... roles) {
        return new CurrentUser(id, neighborhoodId, List.of(roles), List.of());
    }

    @Test
    void creadorPuedeRevocar() {
        Invitation inv = active();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));

        Invitation result = useCase.revoke(user(creatorId, Role.RESIDENT_MEMBER), inv.id());

        assertThat(result.status()).isEqualTo(InvitationStatus.REVOKED);
        verify(invitations).updateStatus(eq(inv.id()), eq(InvitationStatus.REVOKED), eq(creatorId));
    }

    // BR-02: owner del lote puede revocar aunque no sea el creador
    @Test
    void br02OwnerDelLotePuedeRevocarInvitacionAjena() {
        Invitation inv = active();
        UUID ownerId = UUID.randomUUID();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(family.find(ownerId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(ownerId, lotId, true, true, true)));

        Invitation result = useCase.revoke(user(ownerId, Role.RESIDENT_OWNER), inv.id());

        assertThat(result.status()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    void adminPuedeRevocar() {
        Invitation inv = active();
        UUID adminId = UUID.randomUUID();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));

        Invitation result = useCase.revoke(user(adminId, Role.ADMIN), inv.id());

        assertThat(result.status()).isEqualTo(InvitationStatus.REVOKED);
        verifyNoInteractions(family);
    }

    @Test
    void residenteAjenoNoPuedeRevocar() {
        Invitation inv = active();
        UUID otro = UUID.randomUUID();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(family.find(otro, lotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.revoke(user(otro, Role.RESIDENT_MEMBER), inv.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void miembroNoOwnerNoPuedeRevocarAjena() {
        Invitation inv = active();
        UUID otroMiembro = UUID.randomUUID();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(family.find(otroMiembro, lotId)).thenReturn(Optional.of(
                new FamilyMembership(otroMiembro, lotId, false, true, true)));

        assertThatThrownBy(() -> useCase.revoke(user(otroMiembro, Role.RESIDENT_MEMBER), inv.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void invitacionDeOtroBarrioRechaza() {
        Invitation inv = active();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));

        CurrentUser actor = new CurrentUser(creatorId, UUID.randomUUID(), List.of(Role.ADMIN), List.of());
        assertThatThrownBy(() -> useCase.revoke(actor, inv.id()))
                .isInstanceOf(ResourceNotFoundException.class); // #123
    }

    @Test
    void invitacionInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(invitations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.revoke(user(creatorId, Role.ADMIN), id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
