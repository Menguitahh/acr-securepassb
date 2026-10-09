package com.securepass.shared.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #196: cada schema alcanzable desde una respuesta de /api/v1/admin/** lista
 * todos sus campos en required. Un DTO nuevo del admin que no se registre en
 * OpenApiConfig.ADMIN_RESPONSES hace fallar este test: el front no vuelve a
 * recibir campos opcionales por costumbre.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminResponsesRequiredTest {

    @LocalServerPort
    int port;

    @Test
    void lasRespuestasDelAdminNoTienenCamposOpcionales() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v3/api-docs")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode doc = new ObjectMapper().readTree(res.body());
        JsonNode schemas = doc.path("components").path("schemas");

        Deque<String> pending = new ArrayDeque<>();
        Iterator<String> paths = doc.path("paths").fieldNames();
        while (paths.hasNext()) {
            String path = paths.next();
            if (!path.startsWith("/api/v1/admin/")) continue;
            for (JsonNode op : doc.path("paths").path(path)) {
                for (JsonNode response : op.path("responses")) collectRefs(response.path("content"), pending);
            }
        }

        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.pop();
            if (!seen.add(name)) continue;
            JsonNode schema = schemas.path(name);
            Set<String> required = new HashSet<>();
            schema.path("required").forEach(n -> required.add(n.asText()));
            Iterator<String> fields = schema.path("properties").fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                assertThat(required).as(name + "." + field + " tiene que ser required").contains(field);
            }
            collectRefs(schema.path("properties"), pending);
        }
        assertThat(seen).contains("OverviewResponse", "Overstay", "LotResponse", "OnboardUserResponse");
        assertThat(schemas.path("LotResponse").path("properties").path("street").path("nullable").asBoolean()).isTrue();
    }

    private static void collectRefs(JsonNode node, Deque<String> out) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null) out.push(ref.asText().substring(ref.asText().lastIndexOf('/') + 1));
            node.forEach(child -> collectRefs(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectRefs(child, out));
        }
    }
}
