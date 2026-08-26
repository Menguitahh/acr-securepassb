package com.securepass.modules.invitation.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListInvitationsUseCaseTest {

    private InvitationRepository invitations;
    private ListInvitationsUseCase useCase;
    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationRepository.class);
        useCase = new ListInvitationsUseCase(invitations);
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
    }

    private CurrentUser resident(UUID... lots) {
        return new CurrentUser(UUID.randomUUID(), neighborhoodId,
                List.of(Role.RESIDENT_MEMBER), List.of(lots));
    }

    private CurrentUser admin() {
        return new CurrentUser(UUID.randomUUID(), neighborhoodId,
                List.of(Role.ADMIN), List.of());
    }

    @Test
    void residenteFiltraPorSusLotes() {
        when(invitations.list(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new InvitationPage(List.of(), 0, 20, 0));

        useCase.list(resident(lotId), null, null, 0);

        var captor = forClass(InvitationRepository.ListQuery.class);
        verify(invitations).list(captor.capture());
        assertThat(captor.getValue().lotIdsAllowed()).containsExactly(lotId);
    }

    @Test
    void adminNoFiltraPorLotes() {
        when(invitations.list(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new InvitationPage(List.of(), 0, 20, 0));

        useCase.list(admin(), null, InvitationStatus.ACTIVE, 0);

        var captor = forClass(InvitationRepository.ListQuery.class);
        verify(invitations).list(captor.capture());
        assertThat(captor.getValue().lotIdsAllowed()).isEmpty();
        assertThat(captor.getValue().statusFilter()).isEqualTo(InvitationStatus.ACTIVE);
    }

    @Test
    void residenteConLotIdFueraDeSuUnidadRechaza() {
        UUID otro = UUID.randomUUID();

        assertThatThrownBy(() -> useCase.list(resident(lotId), otro, null, 0))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void adminPuedeFiltrarPorCualquierLot() {
        when(invitations.list(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new InvitationPage(List.of(), 0, 20, 0));

        useCase.list(admin(), UUID.randomUUID(), null, 0);

        verify(invitations).list(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void pageNegativaSeNormalizaACero() {
        when(invitations.list(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new InvitationPage(List.of(), 0, 20, 0));

        useCase.list(admin(), null, null, -5);

        var captor = forClass(InvitationRepository.ListQuery.class);
        verify(invitations).list(captor.capture());
        assertThat(captor.getValue().page()).isZero();
    }
}
