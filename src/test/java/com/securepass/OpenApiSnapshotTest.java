package com.securepass;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSnapshotTest {

    private static final Path SNAPSHOT = Path.of("openapi.json");

    @LocalServerPort
    int port;

    @Test
    void openApiSpecCoincideConSnapshot() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/v3/api-docs"))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        JsonNode actual = mapper.readTree(response.body());

        if ("true".equalsIgnoreCase(System.getenv("OPENAPI_UPDATE"))) {
            Files.writeString(SNAPSHOT, pretty(mapper, actual));
            return;
        }

        assertThat(Files.exists(SNAPSHOT))
                .as("openapi.json falta; ejecutar OPENAPI_UPDATE=true ./gradlew test")
                .isTrue();
        JsonNode expected = mapper.readTree(Files.readString(SNAPSHOT));
        assertThat(actual)
                .as("openapi.json desactualizado; ejecutar OPENAPI_UPDATE=true ./gradlew test")
                .isEqualTo(expected);
    }

    private static String pretty(ObjectMapper mapper, JsonNode node) throws Exception {
        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indenter);
        pp.indentArraysWith(indenter);
        return mapper.writer(pp).writeValueAsString(node) + "\n";
    }
}
