package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Membership;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddFamilyMemberUseCaseTest {

    private FamilyMemberRepository family;
    private UserRepository users;
    private AddFamilyMemberUseCase useCase;
    private CurrentUser owner;
    private UUID lotId;
    private UUID neighborhoodId;

    @BeforeEach
    void setUp() {
        family = mock(FamilyMemberRepository.class);
        users = mock(UserRepository.class);
        useCase = new AddFamilyMemberUseCase(family, users);
        UUID ownerId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        owner = new CurrentUser(ownerId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));
        when(family.find(ownerId, lotId))
                .thenReturn(Optional.of(new FamilyMember(ownerId, lotId, null, true, true, true)));
    }

    @Test
    void ownerAgregaMiembroActivoConMembresia() {
        User user = user("Maria", "maria@dev.local");
        when(users.findByIdentifier("maria@dev.local")).thenReturn(Optional.of(user));
        when(family.find(user.id(), lotId)).thenReturn(Optional.empty());
        when(family.neighborhoodIdOfLot(lotId)).thenReturn(neighborhoodId);
        when(family.findMembership(user.id(), neighborhoodId, Role.RESIDENT_MEMBER))
                .thenReturn(Optional.empty());

        FamilyMemberView view = useCase.add(owner, lotId, "maria@dev.local", true, "Hija");

        assertThat(view.userId()).isEqualTo(user.id());
        assertThat(view.canInvite()).isTrue();
        assertThat(view.isOwner()).isFalse();
        verify(family).save(new FamilyMember(user.id(), lotId, "Hija", false, true, true));
        verify(family).saveMembership(any(Membership.class));
    }

    @Test
    void noDuplicaMembresiaExistente() {
        User user = user("Maria", "maria@dev.local");
        when(users.findByIdentifier("maria@dev.local")).thenReturn(Optional.of(user));
        when(family.find(user.id(), lotId)).thenReturn(Optional.empty());
        when(family.neighborhoodIdOfLot(lotId)).thenReturn(neighborhoodId);
        when(family.findMembership(user.id(), neighborhoodId, Role.RESIDENT_MEMBER))
                .thenReturn(Optional.of(new Membership(
                        UUID.randomUUID(), user.id(), neighborhoodId, Role.RESIDENT_MEMBER, true)));

        useCase.add(owner, lotId, "maria@dev.local", false, null);

        verify(family, never()).saveMembership(any(Membership.class));
    }

    @Test
    void soloElTitularAgregaMiembros() {
        UUID memberId = UUID.randomUUID();
        when(family.find(memberId, lotId))
                .thenReturn(Optional.of(new FamilyMember(memberId, lotId, null, false, true, true)));
        CurrentUser member = new CurrentUser(memberId, neighborhoodId, List.of(Role.RESIDENT_MEMBER), List.of(lotId));

        assertThatThrownBy(() -> useCase.add(member, lotId, "maria@dev.local", true, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void usuarioInexistenteDevuelve404() {
        when(users.findByIdentifier("nadie@dev.local")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.add(owner, lotId, "nadie@dev.local", true, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void usuarioSuspendidoDevuelve409() {
        User user = user("Maria", "maria@dev.local");
        when(users.findByIdentifier("maria@dev.local"))
                .thenReturn(Optional.of(new User(user.id(), "maria@dev.local", null, "hash",
                        "Maria", null, UserStatus.SUSPENDED)));

        assertThatThrownBy(() -> useCase.add(owner, lotId, "maria@dev.local", true, null))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("no esta activo");
    }

    @Test
    void miembroDuplicadoDevuelve409() {
        User user = user("Maria", "maria@dev.local");
        when(users.findByIdentifier("maria@dev.local")).thenReturn(Optional.of(user));
        when(family.find(user.id(), lotId))
                .thenReturn(Optional.of(new FamilyMember(user.id(), lotId, null, false, true, true)));

        assertThatThrownBy(() -> useCase.add(owner, lotId, "maria@dev.local", true, null))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("ya es miembro");
    }

    private User user(String name, String email) {
        return new User(UUID.randomUUID(), email, null, "hash", name, null, UserStatus.ACTIVE);
    }
}
