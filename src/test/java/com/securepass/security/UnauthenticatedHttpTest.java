package com.securepass.security;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.Test;
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
 * Sin sesion valida la respuesta es 401, no 403: los clientes refrescan el
 * access token solo ante un 401. Con un 403 el guardia queda trabado en
 * "No se pudo cargar" cuando vence el token, sin refresh ni vuelta al login.
 * Un usuario autenticado sin permiso sigue recibiendo 403.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UnauthenticatedHttpTest {

    private static final String PATH = "/api/v1/admin/metrics/overview";

    @LocalServerPort
    int port;

    @Autowired
    JwtService jwt;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void sinTokenDevuelve401() throws Exception {
        assertThat(get(null).statusCode()).isEqualTo(401);
    }

    @Test
    void tokenInvalidoDevuelve401() throws Exception {
        assertThat(get("abc.def.ghi").statusCode()).isEqualTo(401);
    }

    @Test
    void autenticadoSinPermisoSigueEn403() throws Exception {
        String token = jwt.issue(UUID.randomUUID(), UUID.randomUUID(), List.of(Role.GUARD), List.of()).token();
        assertThat(get(token).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH)).GET();
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
