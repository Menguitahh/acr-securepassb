package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #114: forgot-password responde 204 sin buscar al usuario en el hilo del
 * request; el token se emite en segundo plano. Este test fija que el camino
 * asincrono emite el token de verdad, y que un email desconocido no deja nada.
 */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForgotPasswordAsyncHttpTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    final HttpClient http = HttpClient.newHttpClient();

    private int forgot(String email) throws Exception {
        return http.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/auth/forgot-password"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\"}"))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int resetTokens(UUID userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM activation_tokens WHERE user_id = ? AND purpose = 'PASSWORD_RESET'",
                Integer.class, userId);
    }

    @Test
    void emiteElTokenEnSegundoPlanoParaUnUsuarioActivo() throws Exception {
        UUID user = UUID.randomUUID();
        String email = "reset-" + user + "@t.com";
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'Ana', 'ACTIVE')",
                user, email);

        assertThat(forgot(email.toUpperCase())).isEqualTo(204);

        for (int i = 0; i < 50 && resetTokens(user) == 0; i++) Thread.sleep(100);
        assertThat(resetTokens(user)).isEqualTo(1);
    }

    @Test
    void emailDesconocidoRespondeIgualYNoDejaNada() throws Exception {
        int antes = jdbc.queryForObject("SELECT count(*) FROM activation_tokens", Integer.class);

        assertThat(forgot("nadie-" + UUID.randomUUID() + "@t.com")).isEqualTo(204);

        Thread.sleep(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_tokens", Integer.class)).isEqualTo(antes);
    }
}
