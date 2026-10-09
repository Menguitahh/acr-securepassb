package com.securepass.modules.notification.infrastructure;

import com.securepass.modules.notification.application.DeviceRepository;
import com.securepass.modules.notification.application.PushSender;
import com.securepass.modules.notification.domain.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ExpoPushSenderTest {

    private static final String URL = "https://push.test/send";
    private static final String GOOD = "ExponentPushToken[good-token]";
    private static final String BAD = "ExponentPushToken[bad-token]";
    private static final Instant ENTERED = Instant.parse("2026-08-20T15:00:00Z");
    private static final UUID USER = UUID.randomUUID();
    private static final UUID STAY = UUID.randomUUID();

    private DeviceRepository devices;
    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        devices = mock(DeviceRepository.class);
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private Device device(String token) {
        return new Device(UUID.randomUUID(), USER, token, Device.Platform.IOS, ENTERED, ENTERED);
    }

    private ExpoPushSender sender(String accessToken) {
        RestClient.Builder configured = builder.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (!accessToken.isBlank()) {
            configured = configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return new ExpoPushSender(devices, configured.build(), URL);
    }

    private PushSender.GuestArrival payload() {
        return new PushSender.GuestArrival(STAY, "Maria Lopez", ENTERED, "M4-L12", false);
    }

    @Test
    void enviaMensajeConTokenDeviceYPayloadYAgregaAuthHeaderCuandoHay() {
        when(devices.findByUserId(USER)).thenReturn(List.of(device(GOOD)));
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer secret"))
                .andExpect(jsonPath("$[0].to").value(GOOD))
                .andExpect(jsonPath("$[0].title").value("Tu invitado ingreso"))
                .andExpect(jsonPath("$[0].data.stayId").value(STAY.toString()))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"tk-1\"}]}", MediaType.APPLICATION_JSON));

        sender("secret").notifyGuestArrival(USER, payload());

        server.verify();
        verify(devices, never()).deleteByToken(any());
    }

    // securepassf#33: el aviso lleva la categoria con acciones y el
    // invitationId que usan Extender / Que se retire.
    @Test
    void avisoDePermanenciaLlevaCategoriaEInvitationId() {
        UUID invitation = UUID.randomUUID();
        when(devices.findByUserId(USER)).thenReturn(List.of(device(GOOD)));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$[0].categoryId").value("stay-warning"))
                .andExpect(jsonPath("$[0].data.type").value("stay-warning"))
                .andExpect(jsonPath("$[0].data.invitationId").value(invitation.toString()))
                .andRespond(withSuccess("{\"data\":[{\"status\":\"ok\",\"id\":\"tk-1\"}]}", MediaType.APPLICATION_JSON));

        sender("secret").notifyStayWarning(USER, new PushSender.StayWarning(STAY, invitation, "Maria Lopez", ENTERED, 15));

        server.verify();
    }

    // Requisito 4 del issue: token invalido se limpia de la tabla.
    @Test
    void borraTokenSiExpoReportaDeviceNotRegistered() {
        when(devices.findByUserId(USER)).thenReturn(List.of(device(BAD)));
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"data":[{"status":"error","message":"...","details":{"error":"DeviceNotRegistered"}}]}
                        """, MediaType.APPLICATION_JSON));

        sender("").notifyGuestArrival(USER, payload());

        verify(devices).deleteByToken(BAD);
    }

    // Sin devices no hace HTTP request (evita 400 vacio).
    @Test
    void sinDevicesNoLlamaAExpo() {
        when(devices.findByUserId(USER)).thenReturn(List.of());
        // MockRestServiceServer sin expectativas: cualquier call falla.
        sender("").notifyGuestArrival(USER, payload());
        // sin server.verify() explicito: no hay expectativas y no debe haber calls.
    }

    // Un fallo HTTP no propaga excepcion (bitacora intangible).
    @Test
    void fallo5xxNoPropagaExcepcion() {
        when(devices.findByUserId(USER)).thenReturn(List.of(device(GOOD)));
        server.expect(requestTo(URL)).andRespond(withServerError());

        sender("").notifyGuestArrival(USER, payload());

        verify(devices, never()).deleteByToken(any());
    }
}
