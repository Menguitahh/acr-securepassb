package com.securepass.modules.notification.infrastructure;

import com.securepass.modules.notification.application.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.HashMap;
import java.util.Map;

/**
 * Adapter real contra Resend (https://resend.com). Activado con
 * {@code securepass.email.provider=resend}. API key por env
 * {@code RESEND_API_KEY}.
 *
 * Timeouts cortos (2 s connect / 3 s read): un email colgado no debe
 * atar el executor. Los fallos se loguean y no se propagan: US-50 exige
 * que fallo de envio no rompa la operacion que lo disparo.
 */
@Component
@ConditionalOnProperty(name = "securepass.email.provider", havingValue = "resend")
public class ResendEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(ResendEmailSender.class);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(3);

    private final RestClient http;
    private final String endpoint;
    private final String from;

    public ResendEmailSender(
            @Qualifier("resendRestClient") RestClient http,
            @Value("${securepass.email.resend.url:https://api.resend.com/emails}") String endpoint,
            @Value("${securepass.email.from}") String from) {
        this.http = http;
        this.endpoint = endpoint;
        this.from = from;
    }

    static RestClient defaultHttpClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RESEND_API_KEY vacio con provider=resend");
        }
        HttpClient jdk = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public void send(EmailMessage message) {
        Map<String, Object> body = new HashMap<>();
        body.put("from", from);
        body.put("to", message.to());
        body.put("subject", message.subject());
        if (message.htmlBody() != null && !message.htmlBody().isBlank()) body.put("html", message.htmlBody());
        if (message.textBody() != null && !message.textBody().isBlank()) body.put("text", message.textBody());
        try {
            http.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            LOG.info("email.send provider=resend to={} subject={}", message.to(), message.subject());
        } catch (RestClientException ex) {
            LOG.warn("email.resend.request-failed to={} subject={} error={}",
                    message.to(), message.subject(), ex.getMessage());
        }
    }
}
