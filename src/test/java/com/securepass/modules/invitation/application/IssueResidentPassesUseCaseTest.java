package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.exception.NeighborhoodNotProvisionedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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

class IssueResidentPassesUseCaseTest {

    private ResidentLotsQuery lots;
    private InvitationRepository invitations;
    private SigningKeyRepository signingKeys;
    private QrTokenIssuer qrTokenIssuer;
    private IssueResidentPassesUseCase useCase;
    private UUID userId;
    private UUID neighborhoodId;

    @BeforeEach
    void setUp() {
        lots = mock(ResidentLotsQuery.class);
        invitations = mock(InvitationRepository.class);
        signingKeys = mock(SigningKeyRepository.class);
        qrTokenIssuer = mock(QrTokenIssuer.class);
        useCase = new IssueResidentPassesUseCase(lots, invitations, signingKeys, qrTokenIssuer);
        userId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        when(signingKeys.findActive(neighborhoodId)).thenReturn(Optional.of(new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-active",
                new byte[32], "enc", true, Instant.now(), null)));
        when(qrTokenIssuer.issue(any())).thenReturn("SP1.token");
    }

    @Test
    void sinLotesDevuelveListaVacia() {
        when(lots.listActiveByUser(userId, neighborhoodId)).thenReturn(List.of());

        assertThat(useCase.issue(userId, neighborhoodId)).isEmpty();
        verify(invitations, never()).save(any());
    }

    @Test
    void creaResidentPassCuandoNoExiste() {
        UUID lotId = UUID.randomUUID();
        when(lots.listActiveByUser(userId, neighborhoodId))
                .thenReturn(List.of(new ResidentLot(lotId, "M4-L12", neighborhoodId, "Barrio X")));
        when(invitations.findActiveResidentPass(userId, lotId)).thenReturn(Optional.empty());

        List<ResidentPass> passes = useCase.issue(userId, neighborhoodId);

        assertThat(passes).hasSize(1);
        assertThat(passes.get(0).lotCode()).isEqualTo("M4-L12");
        assertThat(passes.get(0).neighborhoodName()).isEqualTo("Barrio X");
        assertThat(passes.get(0).qrToken()).isEqualTo("SP1.token");

        ArgumentCaptor<Invitation> captor = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).save(captor.capture());
        Invitation saved = captor.getValue();
        assertThat(saved.residentPass()).isTrue();
        assertThat(saved.type()).isEqualTo(InvitationType.EVENT);
        assertThat(saved.createdBy()).isEqualTo(userId);
        // WBS-127: pase permanente post-V17 tiene valid_until FAR_FUTURE
        // (2099-12-31). BR-04 (ventana <= 30 dias para EVENT) NO aplica porque
        // residentPass=true lo exenta. Un cambio futuro que quite esa exencion
        // rompe el flujo normal de residente y este assert es el candado.
        assertThat(saved.validUntil()).isEqualTo(Instant.parse("2099-12-31T23:59:59Z"));
        assertThat(java.time.Duration.between(saved.validFrom(), saved.validUntil()).toDays())
                .isGreaterThan(365 * 70L);
    }

    @Test
    void reusaResidentPassExistenteSinCrearOtro() {
        UUID lotId = UUID.randomUUID();
        Invitation existing = new Invitation(
                UUID.randomUUID(), neighborhoodId, lotId, userId,
                InvitationType.EVENT, InvitationStatus.ACTIVE,
                "Residente", "00000000", 0, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2099-12-31T23:59:59Z"),
                1, 0, "kid-active", UUID.randomUUID(), true, Instant.parse("2026-01-01T00:00:00Z"));
        when(lots.listActiveByUser(userId, neighborhoodId))
                .thenReturn(List.of(new ResidentLot(lotId, "M4-L12", neighborhoodId, "Barrio X")));
        when(invitations.findActiveResidentPass(userId, lotId)).thenReturn(Optional.of(existing));

        useCase.issue(userId, neighborhoodId);

        verify(invitations, never()).save(any());
    }

    @Test
    void sinSigningKeyLanzaNeighborhoodNotProvisioned() {
        UUID lotId = UUID.randomUUID();
        when(lots.listActiveByUser(userId, neighborhoodId))
                .thenReturn(List.of(new ResidentLot(lotId, "M4-L12", neighborhoodId, "Barrio X")));
        when(signingKeys.findActive(neighborhoodId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.issue(userId, neighborhoodId))
                .isInstanceOf(NeighborhoodNotProvisionedException.class)
                .satisfies(ex -> {
                    NeighborhoodNotProvisionedException e = (NeighborhoodNotProvisionedException) ex;
                    assertThat(e.neighborhoodId()).isEqualTo(neighborhoodId);
                    assertThat(e.resource()).isEqualTo("signing key activa");
                });
        verify(invitations, never()).save(any());
    }
}
