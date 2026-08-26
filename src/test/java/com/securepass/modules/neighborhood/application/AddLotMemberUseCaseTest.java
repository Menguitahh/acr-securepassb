package com.securepass.modules.neighborhood.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotMember;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.exception.StateConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddLotMemberUseCaseTest {

    private final UUID neighborhood = UUID.randomUUID();
    private final UUID lotId = UUID.randomUUID();
    private LotRepository lots;
    private LotMemberRepository members;
    private MembershipGranter granter;
    private AddLotMemberUseCase useCase;

    @BeforeEach
    void setUp() {
        lots = mock(LotRepository.class);
        members = mock(LotMemberRepository.class);
        granter = mock(MembershipGranter.class);
        useCase = new AddLotMemberUseCase(lots, members, granter);
        Instant now = Instant.now();
        when(lots.findById(lotId)).thenReturn(Optional.of(
                new Lot(lotId, neighborhood, "M4", null, null, LotStatus.ACTIVE, now, now)));
    }

    @Test
    void owner_recibe_rol_resident_owner_y_can_invite_forzado() {
        UUID user = UUID.randomUUID();
        when(members.find(lotId, user)).thenReturn(Optional.empty());
        when(members.findOwner(lotId)).thenReturn(Optional.empty());

        LotMember saved = useCase.add(neighborhood, lotId,
                new AddLotMemberUseCase.Command(user, "Titular", true, false));

        assertThat(saved.isOwner()).isTrue();
        assertThat(saved.canInvite()).isTrue();
        verify(granter).grant(user, neighborhood, Role.RESIDENT_OWNER);
    }

    @Test
    void miembro_no_owner_recibe_resident_member() {
        UUID user = UUID.randomUUID();
        when(members.find(lotId, user)).thenReturn(Optional.empty());

        useCase.add(neighborhood, lotId,
                new AddLotMemberUseCase.Command(user, "Hijo/a", false, true));

        verify(granter).grant(user, neighborhood, Role.RESIDENT_MEMBER);
    }

    @Test
    void rechaza_segundo_owner() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        when(members.find(lotId, user)).thenReturn(Optional.empty());
        when(members.findOwner(lotId)).thenReturn(Optional.of(
                new LotMember(UUID.randomUUID(), lotId, UUID.randomUUID(), "Titular", true, true, true, now)));

        assertThatThrownBy(() -> useCase.add(neighborhood, lotId,
                new AddLotMemberUseCase.Command(user, "Titular", true, true)))
                .isInstanceOf(StateConflictException.class);
    }

    @Test
    void rechaza_usuario_duplicado_en_el_lote() {
        UUID user = UUID.randomUUID();
        Instant now = Instant.now();
        when(members.find(lotId, user)).thenReturn(Optional.of(
                new LotMember(UUID.randomUUID(), lotId, user, "Hijo/a", false, true, true, now)));

        assertThatThrownBy(() -> useCase.add(neighborhood, lotId,
                new AddLotMemberUseCase.Command(user, "Hijo/a", false, true)))
                .isInstanceOf(StateConflictException.class);
    }

    @Test
    void owner_captura_correcta_de_argumentos() {
        UUID user = UUID.randomUUID();
        when(members.find(lotId, user)).thenReturn(Optional.empty());
        when(members.findOwner(lotId)).thenReturn(Optional.empty());

        useCase.add(neighborhood, lotId,
                new AddLotMemberUseCase.Command(user, "Titular", true, false));

        ArgumentCaptor<LotMember> captor = ArgumentCaptor.forClass(LotMember.class);
        verify(members).save(captor.capture());
        assertThat(captor.getValue().lotId()).isEqualTo(lotId);
        assertThat(captor.getValue().userId()).isEqualTo(user);
    }
}
