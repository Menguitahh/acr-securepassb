package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.NeighborhoodNotProvisionedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CreateInvitationUseCaseTest {

    private InvitationRepository invitations;
    private FamilyMemberQuery family;
    private LotStatusPort lots;
    private SigningKeyRepository signingKeys;
    private QrTokenIssuer qrTokenIssuer;
    private CreateInvitationUseCase useCase;
    private UUID userId;
    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        invitations = mock(InvitationRepository.class);
        family = mock(FamilyMemberQuery.class);
        lots = mock(LotStatusPort.class);
        signingKeys = mock(SigningKeyRepository.class);
        qrTokenIssuer = mock(QrTokenIssuer.class);
        useCase = new CreateInvitationUseCase(invitations, family, lots, signingKeys, qrTokenIssuer,
                "https://securepass.test/i");
        userId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
    }

    private void stubActiveMember(boolean canInvite) {
        when(family.find(userId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(userId, lotId, false, canInvite, true)));
        when(lots.find(lotId)).thenReturn(Optional.of("ACTIVE"));
        when(lots.findNeighborhoodId(lotId)).thenReturn(Optional.of(neighborhoodId));
        SigningKey key = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                new byte[32], "enc", true, Instant.now(), null);
        when(signingKeys.findActive(neighborhoodId)).thenReturn(Optional.of(key));
        when(qrTokenIssuer.issue(any())).thenReturn("SP1.token");
    }

    private CreateInvitationUseCase.Command singleCommand() {
        Instant from = Instant.now();
        return new CreateInvitationUseCase.Command(
                lotId, InvitationType.SINGLE,
                "Maria Lopez", "30123456", 2, "cumple",
                from, from.plus(Duration.ofHours(6)),
                1);
    }

    @Test
    void creaInvitacionYEmiteQr() {
        stubActiveMember(true);

        IssuedInvitation issued = useCase.create(userId, neighborhoodId, singleCommand());

        assertThat(issued.invitation().createdBy()).isEqualTo(userId);
        assertThat(issued.invitation().neighborhoodId()).isEqualTo(neighborhoodId);
        assertThat(issued.invitation().tokenKid()).isEqualTo("kid-1");
        assertThat(issued.invitation().jti()).isNotNull();
        assertThat(issued.qrToken()).isEqualTo("SP1.token");
        assertThat(issued.shareUrl()).startsWith("https://securepass.test/i/");
        verify(invitations).save(any());
    }

    @Test
    void jtiPersistidoCoincideConElUsadoAlFirmarQr() {
        stubActiveMember(true);
        var captor = org.mockito.ArgumentCaptor.forClass(QrTokenIssuer.IssueCommand.class);

        IssuedInvitation issued = useCase.create(userId, neighborhoodId, singleCommand());

        verify(qrTokenIssuer).issue(captor.capture());
        assertThat(captor.getValue().jti()).isEqualTo(issued.invitation().jti());
    }

    // BR-11: el DNI no viaja en el payload del QR
    @Test
    void br11DniNoViajaEnElPayloadDelQr() {
        stubActiveMember(true);
        var captor = org.mockito.ArgumentCaptor.forClass(QrTokenIssuer.IssueCommand.class);

        useCase.create(userId, neighborhoodId, singleCommand());

        verify(qrTokenIssuer).issue(captor.capture());
        assertThat(captor.getValue().toString()).doesNotContain("30123456");
    }

    // BR-01: sin canInvite falla
    @Test
    void br01SinCanInviteRechaza() {
        stubActiveMember(false);

        assertThatThrownBy(() -> useCase.create(userId, neighborhoodId, singleCommand()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).rule()).isEqualTo("BR-01"));
        verifyNoInteractions(invitations, qrTokenIssuer);
    }

    // BR-01: sin membership rechaza
    @Test
    void br01SinMembershipRechaza() {
        when(family.find(userId, lotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.create(userId, neighborhoodId, singleCommand()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).rule()).isEqualTo("BR-01"));
    }

    // BR-01: miembro inactivo rechaza
    @Test
    void br01MiembroInactivoRechaza() {
        when(family.find(userId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(userId, lotId, false, true, false)));

        assertThatThrownBy(() -> useCase.create(userId, neighborhoodId, singleCommand()))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void sinSigningKeyLanzaNeighborhoodNotProvisioned() {
        when(family.find(userId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(userId, lotId, false, true, true)));
        when(lots.find(lotId)).thenReturn(Optional.of("ACTIVE"));
        when(lots.findNeighborhoodId(lotId)).thenReturn(Optional.of(neighborhoodId));
        when(signingKeys.findActive(neighborhoodId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.create(userId, neighborhoodId, singleCommand()))
                .isInstanceOf(NeighborhoodNotProvisionedException.class)
                .satisfies(ex -> {
                    NeighborhoodNotProvisionedException e = (NeighborhoodNotProvisionedException) ex;
                    assertThat(e.neighborhoodId()).isEqualTo(neighborhoodId);
                    assertThat(e.resource()).isEqualTo("signing key activa");
                });
        verifyNoInteractions(invitations, qrTokenIssuer);
    }

    // BR-04 violacion en dominio se convierte a 422
    @Test
    void ventanaMuyLargaSeConvierteEnBusinessRuleViolation() {
        stubActiveMember(true);
        Instant from = Instant.now();
        CreateInvitationUseCase.Command cmd = new CreateInvitationUseCase.Command(
                lotId, InvitationType.SINGLE,
                "X", "30123456", 0, null,
                from, from.plus(Duration.ofDays(31)),
                1);

        assertThatThrownBy(() -> useCase.create(userId, neighborhoodId, cmd))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).rule()).isEqualTo("BR-04"));
    }

    @Test
    void eventValidoSeCrea() {
        stubActiveMember(true);
        Instant from = Instant.now();
        CreateInvitationUseCase.Command cmd = new CreateInvitationUseCase.Command(
                lotId, InvitationType.EVENT,
                "Fiesta", "30123456", 0, null,
                from, from.plus(Duration.ofDays(1)),
                50);

        IssuedInvitation issued = useCase.create(userId, neighborhoodId, cmd);

        assertThat(issued.invitation().type()).isEqualTo(InvitationType.EVENT);
        assertThat(issued.invitation().maxUses()).isEqualTo(50);
    }

    // BR-03: lote suspendido rechaza nueva invitacion
    @Test
    void br03LoteSuspendidoRechazaCreacion() {
        when(family.find(userId, lotId)).thenReturn(Optional.of(
                new FamilyMembership(userId, lotId, false, true, true)));
        when(lots.find(lotId)).thenReturn(Optional.of("SUSPENDED"));

        assertThatThrownBy(() -> useCase.create(userId, neighborhoodId, singleCommand()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(ex -> assertThat(((BusinessRuleViolationException) ex).rule()).isEqualTo("BR-03"));
        verifyNoInteractions(invitations, qrTokenIssuer);
    }
}
