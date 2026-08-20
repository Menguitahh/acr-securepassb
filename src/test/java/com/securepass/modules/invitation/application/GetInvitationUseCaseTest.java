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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GetInvitationUseCaseTest {

    private InvitationRepository invitations;
    private FamilyMemberQuery family;
    private GetInvitationUseCase useCase;
    private UUID neighborhoodId;
    private UUID lotId;
    private UUID creatorId;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationRepository.class);
        family = mock(FamilyMemberQuery.class);
        useCase = new GetInvitationUseCase(invitations, family);
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
    }

    private Invitation invitation() {
        Instant now = Instant.parse("2026-08-19T12:00:00Z");
        return new Invitation(
                UUID.randomUUID(), neighborhoodId, lotId, creatorId,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                now, now.plus(Duration.ofHours(6)),
                null, null, null, 1, 0, "kid-1", UUID.randomUUID(), false, now);
    }

    private CurrentUser user(UUID id, Role... roles) {
        return new CurrentUser(id, neighborhoodId, List.of(roles), List.of());
    }

    @Test
    void creadorPuedeVer() {
        Invitation inv = invitation();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));

        assertThat(useCase.get(user(creatorId, Role.RESIDENT_MEMBER), inv.id())).isEqualTo(inv);
    }

    @Test
    void ownerDelLotePuedeVer() {
        Invitation inv = invitation();
        UUID ownerId = UUID.randomUUID();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(family.find(ownerId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(ownerId, lotId, true, true, true)));

        assertThat(useCase.get(user(ownerId, Role.RESIDENT_OWNER), inv.id())).isEqualTo(inv);
    }

    @Test
    void adminPuedeVer() {
        Invitation inv = invitation();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));

        assertThat(useCase.get(user(UUID.randomUUID(), Role.ADMIN), inv.id())).isEqualTo(inv);
    }

    @Test
    void miembroNoOwnerNoPuedeVerAjena() {
        Invitation inv = invitation();
        UUID otro = UUID.randomUUID();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        when(family.find(otro, lotId)).thenReturn(Optional.of(
                new FamilyMembership(otro, lotId, false, true, true)));

        assertThatThrownBy(() -> useCase.get(user(otro, Role.RESIDENT_MEMBER), inv.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void otroBarrioRechaza() {
        Invitation inv = invitation();
        when(invitations.findById(inv.id())).thenReturn(Optional.of(inv));
        CurrentUser actor = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(),
                List.of(Role.ADMIN), List.of());

        assertThatThrownBy(() -> useCase.get(actor, inv.id()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void inexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(invitations.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.get(user(creatorId, Role.ADMIN), id))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
