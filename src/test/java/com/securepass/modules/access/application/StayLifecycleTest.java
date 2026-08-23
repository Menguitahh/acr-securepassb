package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.Stay;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StayLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    private StayRepository stays;
    private ApplicationEventPublisher events;
    private LotStatusQuery lots;
    private StayLifecycle lifecycle;
    private UUID neighborhoodId;
    private UUID lotId;
    private UUID invitationId;
    private UUID gateId;
    private UUID guardId;

    @BeforeEach
    void setUp() {
        stays = mock(StayRepository.class);
        events = mock(ApplicationEventPublisher.class);
        lots = mock(LotStatusQuery.class);
        lifecycle = new StayLifecycle(stays, events, lots);
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        gateId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        when(stays.openIfNotOpen(any(Stay.class))).thenAnswer(inv -> inv.getArgument(0));
        when(lots.find(any(UUID.class))).thenReturn(Optional.of(new LotSnapshot(
                lotId, "M4-L12", com.securepass.modules.access.domain.LotStatus.ACTIVE, ZoneId.of("UTC"))));
    }

    private Invitation guestInvitation(boolean residentPass) {
        return new Invitation(
                invitationId, neighborhoodId, lotId, UUID.randomUUID(),
                residentPass ? InvitationType.RECURRING : InvitationType.SINGLE,
                InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 2, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)),
                residentPass ? java.util.List.of(1, 2, 3, 4, 5, 6, 7) : null,
                residentPass ? java.time.LocalTime.of(0, 0) : null,
                residentPass ? java.time.LocalTime.of(23, 59) : null,
                1, 0, "kid-1", UUID.randomUUID(), residentPass, NOW);
    }

    private AccessLog log(Direction direction, boolean allowed, DenialReason reason) {
        return new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, invitationId, lotId,
                direction, SubjectType.GUEST, allowed, reason,
                "Maria Lopez", "30123456", 2, UUID.randomUUID(), NOW, false, false, UUID.randomUUID());
    }

    // BR-14: ingreso ALLOWED abre stay con expected_until = invitation.valid_until.
    @Test
    void allowedInAbreStay() {
        Invitation inv = guestInvitation(false);

        Optional<Stay> opened = lifecycle.onAllowed(log(Direction.IN, true, null), inv);

        assertThat(opened).isPresent();
        ArgumentCaptor<Stay> captor = ArgumentCaptor.forClass(Stay.class);
        verify(stays).openIfNotOpen(captor.capture());
        Stay saved = captor.getValue();
        assertThat(saved.invitationId()).isEqualTo(invitationId);
        assertThat(saved.expectedUntil()).isEqualTo(inv.validUntil());
        assertThat(saved.isOpen()).isTrue();
    }

    // BR-14: egreso ALLOWED cierra la stay abierta.
    @Test
    void allowedOutCierraStayAbierta() {
        Invitation inv = guestInvitation(false);
        Stay open = new Stay(
                UUID.randomUUID(), neighborhoodId, invitationId, lotId,
                "Maria Lopez", "30123456", 2,
                UUID.randomUUID(), null, NOW.minus(Duration.ofHours(1)), null,
                NOW.plus(Duration.ofHours(6)), false, null);
        when(stays.findOpenByInvitationId(invitationId)).thenReturn(Optional.of(open));

        Optional<Stay> closed = lifecycle.onAllowed(log(Direction.OUT, true, null), inv);

        assertThat(closed).isPresent();
        assertThat(closed.get().isOpen()).isFalse();
        assertThat(closed.get().closedManually()).isFalse();
        verify(stays).close(any(Stay.class));
    }

    // BR-07: egreso sin ingreso previo no crea stay ni la fuerza.
    @Test
    void egresoSinIngresoPrevioNoRompeNiCrea() {
        Invitation inv = guestInvitation(false);
        when(stays.findOpenByInvitationId(invitationId)).thenReturn(Optional.empty());

        Optional<Stay> result = lifecycle.onAllowed(log(Direction.OUT, true, null), inv);

        assertThat(result).isEmpty();
        verify(stays, never()).close(any());
        verify(stays, never()).openIfNotOpen(any());
    }

    // BR-10: el residentPass no abre stay (US-16).
    @Test
    void residentPassNoAbreStay() {
        Invitation inv = guestInvitation(true);

        Optional<Stay> result = lifecycle.onAllowed(log(Direction.IN, true, null), inv);

        assertThat(result).isEmpty();
        verify(stays, never()).openIfNotOpen(any());
    }

    // Un log DENIED no toca stays.
    @Test
    void deniedNoToca() {
        Invitation inv = guestInvitation(false);

        Optional<Stay> result = lifecycle.onAllowed(log(Direction.IN, false, DenialReason.EXPIRED), inv);

        assertThat(result).isEmpty();
        verify(stays, never()).openIfNotOpen(any());
        verify(stays, never()).close(any());
    }

    // Un segundo IN con la misma invitacion no crea otra stay (unique index)
    // ni republica el evento (evita re-push cuando el guardia escanea 2 veces).
    @Test
    void segundoInDevuelveStayExistenteYNoRepublicaEvento() {
        Invitation inv = guestInvitation(false);
        Stay existing = new Stay(
                UUID.randomUUID(), neighborhoodId, invitationId, lotId,
                "Maria Lopez", "30123456", 2,
                UUID.randomUUID(), null, NOW.minus(Duration.ofMinutes(30)), null,
                NOW.plus(Duration.ofHours(6)), false, null);
        when(stays.openIfNotOpen(any(Stay.class))).thenReturn(existing);

        Optional<Stay> result = lifecycle.onAllowed(log(Direction.IN, true, null), inv);

        assertThat(result).contains(existing);
        verify(events, never()).publishEvent(any(GuestArrivedEvent.class));
    }

    // WBS-43: ingreso nuevo publica GuestArrivedEvent con enteredAt = capturedAt
    // del log (no "now"), para respetar la hora real en sync offline.
    @Test
    void allowedInPublicaGuestArrivedEvent() {
        Invitation inv = guestInvitation(false);

        lifecycle.onAllowed(log(Direction.IN, true, null), inv);

        ArgumentCaptor<GuestArrivedEvent> captor = ArgumentCaptor.forClass(GuestArrivedEvent.class);
        verify(events).publishEvent(captor.capture());
        GuestArrivedEvent ev = captor.getValue();
        assertThat(ev.invitationId()).isEqualTo(invitationId);
        assertThat(ev.residentUserId()).isEqualTo(inv.createdBy());
        assertThat(ev.subjectName()).isEqualTo("Maria Lopez");
        assertThat(ev.enteredAt()).isEqualTo(NOW);
        assertThat(ev.lotCode()).isEqualTo("M4-L12");
        assertThat(ev.syncedOffline()).isFalse();
    }

    // WBS-43: para eventos sincronizados offline, el evento lleva la hora real
    // (capturedAt del scan) y syncedOffline = true.
    @Test
    void ingresoOfflinePublicaEventoConHoraRealYFlagSync() {
        Invitation inv = guestInvitation(false);
        AccessLog syncedLog = new AccessLog(
                UUID.randomUUID(), neighborhoodId, gateId, guardId, invitationId, lotId,
                Direction.IN, SubjectType.GUEST, true, null,
                "Maria Lopez", "30123456", 2, UUID.randomUUID(),
                NOW.minus(Duration.ofHours(2)), true, false, UUID.randomUUID());

        lifecycle.onAllowed(syncedLog, inv);

        ArgumentCaptor<GuestArrivedEvent> captor = ArgumentCaptor.forClass(GuestArrivedEvent.class);
        verify(events).publishEvent(captor.capture());
        assertThat(captor.getValue().enteredAt()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(captor.getValue().syncedOffline()).isTrue();
    }

    // DENIED y OUT no disparan el evento.
    @Test
    void deniedYOutNoPublicanEvento() {
        Invitation inv = guestInvitation(false);
        lifecycle.onAllowed(log(Direction.IN, false, DenialReason.EXPIRED), inv);
        when(stays.findOpenByInvitationId(invitationId)).thenReturn(Optional.empty());
        lifecycle.onAllowed(log(Direction.OUT, true, null), inv);

        verify(events, never()).publishEvent(any(GuestArrivedEvent.class));
    }
}
