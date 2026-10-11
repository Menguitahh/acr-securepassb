package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtTokenProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Health e info quedan publicos: los usan las probes del orquestador.
 * Metrics y prometheus revelan rutas, latencias y el estado del pool: solo
 * el duenio de la plataforma los lee.
 *
 * El application.yml de test reemplaza al de main en el classpath: la
 * exposicion de main/application.yml se repite aca para probarla. Spring
 * Boot apaga el export de metricas en tests: se prende para que exista
 * /actuator/prometheus.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=health,info,metrics,prometheus",
        "management.endpoint.health.probes.enabled=true",
        "management.prometheus.metrics.export.enabled=true"})
class ActuatorExposureHttpTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtTokenProvider jwt;

    private final HttpClient http = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info"})
    void probesSonPublicas(String path) throws Exception {
        assertThat(get(path, null).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/metrics", "/actuator/prometheus"})
    void metricasSinTokenDevuelven401(String path) throws Exception {
        assertThat(get(path, null).statusCode()).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/metrics", "/actuator/prometheus"})
    void metricasConAdminDeBarrioDevuelven403(String path) throws Exception {
        assertThat(get(path, token(Role.ADMIN)).statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/metrics", "/actuator/prometheus"})
    void metricasConDuenioDeLaPlataformaDevuelven200(String path) throws Exception {
        assertThat(get(path, token(Role.PLATFORM_ADMIN)).statusCode()).isEqualTo(200);
    }

    private String token(Role role) {
        return jwt.issue(UUID.randomUUID(), null, List.of(role), List.of()).token();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
