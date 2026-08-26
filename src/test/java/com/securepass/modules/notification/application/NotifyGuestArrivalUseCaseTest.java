package com.securepass.modules.notification.application;

import com.securepass.modules.access.application.GuestArrivedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotifyGuestArrivalUseCaseTest {

    private GuestArrivedEvent event(boolean synced) {
        return new GuestArrivedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Maria Lopez",
                "M4-L12",
                Instant.parse("2026-08-20T15:00:00Z"),
                synced);
    }

    @Test
    void llamaAlPushConPayloadCompleto() {
        PushSender push = mock(PushSender.class);
        NotifyGuestArrivalUseCase useCase = new NotifyGuestArrivalUseCase(push);
        GuestArrivedEvent ev = event(false);

        useCase.onGuestArrived(ev);

        ArgumentCaptor<PushSender.GuestArrival> captor = ArgumentCaptor.forClass(PushSender.GuestArrival.class);
        verify(push).notifyGuestArrival(eq(ev.residentUserId()), captor.capture());
        PushSender.GuestArrival payload = captor.getValue();
        assertThat(payload.stayId()).isEqualTo(ev.stayId());
        assertThat(payload.subjectName()).isEqualTo("Maria Lopez");
        assertThat(payload.enteredAt()).isEqualTo(ev.enteredAt());
        assertThat(payload.lotCode()).isEqualTo("M4-L12");
        assertThat(payload.syncedOffline()).isFalse();
    }

    // Un fallo del adapter Expo NO debe propagar: el ingreso ya fue commiteado
    // y la bitacora es intangible (spec 5.5).
    @Test
    void tragaExceptionDelAdapter() {
        PushSender push = mock(PushSender.class);
        doThrow(new RuntimeException("expo caido")).when(push).notifyGuestArrival(any(), any());
        NotifyGuestArrivalUseCase useCase = new NotifyGuestArrivalUseCase(push);

        assertThatCode(() -> useCase.onGuestArrived(event(true))).doesNotThrowAnyException();
    }
}
