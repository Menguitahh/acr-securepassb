package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateFamilyMemberUseCaseTest {

    private FamilyMemberRepository family;
    private UserRepository users;
    private ActiveInvitationRevoker revoker;
    private UpdateFamilyMemberUseCase useCase;
    private CurrentUser owner;
    private UUID ownerId;
    private UUID lotId;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        family = mock(FamilyMemberRepository.class);
        users = mock(UserRepository.class);
        revoker = mock(ActiveInvitationRevoker.class);
        useCase = new UpdateFamilyMemberUseCase(family, users, revoker);
        ownerId = UUID.randomUUID();
        UUID neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        memberId = UUID.randomUUID();
        owner = new CurrentUser(ownerId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));
        when(family.find(ownerId, lotId))
                .thenReturn(Optional.of(new FamilyMember(ownerId, lotId, null, true, true, true)));
        when(users.findById(memberId)).thenReturn(Optional.of(
                new User(memberId, "maria@dev.local", null, "hash", "Maria", null, null, UserStatus.ACTIVE)));
    }

    @Test
    void cambiaCanInviteSinRevocar() {
        when(family.find(memberId, lotId))
                .thenReturn(Optional.of(new FamilyMember(memberId, lotId, null, false, false, true)));

        FamilyMemberView view = useCase.update(owner, lotId, memberId, true, null);

        assertThat(view.canInvite()).isTrue();
        assertThat(view.active()).isTrue();
        verify(family).update(memberId, lotId, true, true);
        verify(revoker, never()).revokeActiveByLotAndUser(lotId, memberId, ownerId);
    }

    @Test
    void darDeBajaRevocaInvitacionesEnCascada() {
        when(family.find(memberId, lotId))
                .thenReturn(Optional.of(new FamilyMember(memberId, lotId, null, false, true, true)));

        FamilyMemberView view = useCase.update(owner, lotId, memberId, null, false);

        assertThat(view.active()).isFalse();
        verify(revoker).revokeActiveByLotAndUser(lotId, memberId, ownerId);
        verify(family).update(memberId, lotId, true, false);
    }

    @Test
    void reactivarMiembroNoRevoca() {
        when(family.find(memberId, lotId))
                .thenReturn(Optional.of(new FamilyMember(memberId, lotId, null, false, true, false)));

        useCase.update(owner, lotId, memberId, null, true);

        verify(revoker, never()).revokeActiveByLotAndUser(lotId, memberId, ownerId);
        verify(family).update(memberId, lotId, true, true);
    }

    @Test
    void elTitularNoSeModifica() {
        when(family.find(memberId, lotId))
                .thenReturn(Optional.of(new FamilyMember(memberId, lotId, null, true, true, true)));

        assertThatThrownBy(() -> useCase.update(owner, lotId, memberId, null, false))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("titular no se modifica");
    }

    @Test
    void soloElTitularModificaMiembros() {
        UUID memberActorId = UUID.randomUUID();
        CurrentUser memberActor = new CurrentUser(
                memberActorId, owner.neighborhoodId(), List.of(Role.RESIDENT_MEMBER), List.of(lotId));
        when(family.find(memberActorId, lotId))
                .thenReturn(Optional.of(new FamilyMember(memberActorId, lotId, null, false, true, true)));

        assertThatThrownBy(() -> useCase.update(memberActor, lotId, memberId, true, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void miembroInexistenteDevuelve404() {
        when(family.find(memberId, lotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(owner, lotId, memberId, true, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void requiereAlMenosUnCampo() {
        assertThatThrownBy(() -> useCase.update(owner, lotId, memberId, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
