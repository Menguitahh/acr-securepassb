package com.securepass.modules.notification.infrastructure;

import com.securepass.modules.notification.application.DeviceRepository;
import com.securepass.modules.notification.application.PushSender;
import com.securepass.modules.notification.domain.Device;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adapter real contra Expo Push Service. Activado cuando
 * `securepass.push.provider=expo`. Batchea hasta 100 mensajes por request
 * (limite de Expo) y borra tokens que Expo reporta como
 * `DeviceNotRegistered`.
 *
 * Timeouts cortos (2 s connect / 3 s read): un push que se cuelga no
 * bloquea el executor de push y limita la ventana de errores acumulados.
 * Aun asi el listener corre en `pushExecutor` (virtual threads) para no
 * tocar el hilo del request.
 *
 * ponytail: no consulta el endpoint de receipts (delivery status). Solo
 * limpia tokens por errores sincronicos del ticket. La limpieza completa
 * por receipts es upgrade futuro si se detectan tokens muertos acumulados.
 */
@Component
@ConditionalOnProperty(name = "securepass.push.provider", havingValue = "expo")
public class ExpoPushSender implements PushSender {

    private static final Logger LOG = LoggerFactory.getLogger(ExpoPushSender.class);
    static final String STAY_WARNING_CATEGORY = "stay-warning";
    private static final int EXPO_BATCH_LIMIT = 100;
    private static final String DEVICE_NOT_REGISTERED = "DeviceNotRegistered";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("America/Argentina/Buenos_Aires"));

    private final DeviceRepository devices;
    private final RestClient http;
    private final String endpoint;

    public ExpoPushSender(
            DeviceRepository devices,
            RestClient http,
            @Value("${securepass.push.expo.url:https://exp.host/--/api/v2/push/send}") String endpoint) {
        this.devices = devices;
        this.http = http;
        this.endpoint = endpoint;
    }

    /**
     * Construye el RestClient para produccion: `JdkClientHttpRequestFactory`
     * con timeout de conexion de 2 s y de lectura de 3 s. `Authorization`
     * opcional para cuentas con `EXPO_ACCESS_TOKEN`. Los tests inyectan un
     * RestClient distinto (typicamente con `MockRestServiceServer`).
     */
    static RestClient defaultHttpClient(String accessToken) {
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        if (accessToken != null && !accessToken.isBlank()) {
            builder = builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        return builder.build();
    }

    @Override
    public void notifyStayWarning(UUID residentUserId, StayWarning payload) {
        String title = "Tu invitado esta por vencer";
        String body = payload.subjectName() + " lleva casi todo el tiempo permitido. Faltan " + payload.minutesRemaining() + " min.";
        Map<String, Object> data = Map.of(
                "type", "stay-warning",
                "stayId", payload.stayId().toString(),
                "invitationId", payload.invitationId().toString(),
                "minutesRemaining", payload.minutesRemaining());
        // securepassf#33: la app registra la categoria "stay-warning" con los
        // botones Extender y Que se retire.
        send(residentUserId, title, body, data, STAY_WARNING_CATEGORY);
    }

    @Override
    public void notifyGuestArrival(UUID residentUserId, GuestArrival payload) {
        String hora = HHMM.format(payload.enteredAt());
        String title = "Tu invitado ingreso";
        String body = payload.subjectName() + " ingreso a las " + hora
                + (payload.lotCode() == null ? "" : " (lote " + payload.lotCode() + ")");
        Map<String, Object> data = Map.of(
                "type", "guest-arrival",
                "stayId", payload.stayId().toString(),
                "enteredAt", payload.enteredAt().toString(),
                "syncedOffline", payload.syncedOffline());
        send(residentUserId, title, body, data, null);
    }

    private void send(UUID userId, String title, String body, Map<String, Object> data, String categoryId) {
        List<Device> targets = devices.findByUserId(userId);
        if (targets.isEmpty()) {
            LOG.debug("push.skip user={} sin devices registrados", userId);
            return;
        }
        List<Map<String, Object>> messages = new ArrayList<>(targets.size());
        List<String> tokens = new ArrayList<>(targets.size());
        for (Device d : targets) {
            tokens.add(d.expoPushToken());
            Map<String, Object> message = new java.util.HashMap<>(Map.of(
                    "to", d.expoPushToken(),
                    "title", title,
                    "body", body,
                    "data", data,
                    "sound", "default",
                    "priority", "high",
                    "channelId", "default"));
            if (categoryId != null) message.put("categoryId", categoryId);
            messages.add(message);
        }
        for (int i = 0; i < messages.size(); i += EXPO_BATCH_LIMIT) {
            int to = Math.min(i + EXPO_BATCH_LIMIT, messages.size());
            postBatch(messages.subList(i, to), tokens.subList(i, to));
        }
    }

    private void postBatch(List<Map<String, Object>> batch, List<String> batchTokens) {
        ExpoResponse resp;
        try {
            resp = http.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(batch)
                    .retrieve()
                    .body(ExpoResponse.class);
        } catch (RestClientException ex) {
            // No propagamos: un fallo de red al servicio Expo no debe romper el flujo
            // de acceso (spec 5.5: integridad de bitacora no depende de push).
            LOG.warn("push.expo.request-failed size={} error={}", batch.size(), ex.getMessage());
            return;
        }
        if (resp == null || resp.data() == null) {
            LOG.warn("push.expo.empty-response size={}", batch.size());
            return;
        }
        List<Ticket> tickets = resp.data();
        for (int i = 0; i < tickets.size() && i < batchTokens.size(); i++) {
            Ticket t = tickets.get(i);
            if (t == null || "ok".equals(t.status())) continue;
            String errorCode = t.details() == null ? null : t.details().error();
            if (DEVICE_NOT_REGISTERED.equals(errorCode)) {
                boolean removed = devices.deleteByToken(batchTokens.get(i));
                LOG.info("push.expo.token-cleaned token-suffix={} removed={}",
                        suffix(batchTokens.get(i)), removed);
                continue;
            }
            LOG.warn("push.expo.ticket-error token-suffix={} status={} error={} msg={}",
                    suffix(batchTokens.get(i)), t.status(), errorCode, t.message());
        }
    }

    private static String suffix(String token) {
        return token.length() <= 8 ? token : "..." + token.substring(token.length() - 8);
    }

    // Payloads Expo (subset: solo lo que consumimos). Cualquier campo extra se ignora.
    private record ExpoResponse(List<Ticket> data) {}

    private record Ticket(String status, String id, String message, TicketDetails details) {}

    private record TicketDetails(String error) {}
}
