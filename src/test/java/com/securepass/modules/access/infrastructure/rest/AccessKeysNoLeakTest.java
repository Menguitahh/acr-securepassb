package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import com.securepass.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccessKeysNoLeakTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SigningKeyIssuer issuer;

    @Autowired
    JwtService jwt;

    @Test
    void endpointNoFiltraNiLaPrivadaCifradaNiLaBrutaNiCamposSospechosos() throws Exception {
        UUID nid = seedNeighborhood();
        SigningKeyIssuer.IssuedKey issued = issuer.issueForNeighborhood(nid);
        String storedCiphertext = jdbc.sql("SELECT private_key_enc FROM signing_keys WHERE neighborhood_id = :nid")
                .param("nid", nid)
                .query(String.class)
                .single();
        assertThat(storedCiphertext).isNotBlank();

        String jwtToken = jwt.issue(UUID.randomUUID(), nid, List.of(Role.GUARD), List.of()).token();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/access/keys"))
                .header("Authorization", "Bearer " + jwtToken)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();

        assertThat(body).doesNotContain(storedCiphertext);
        assertThat(body.toLowerCase()).doesNotContain("private");
        assertThat(body.toLowerCase()).doesNotContain("seed");
        assertThat(body.toLowerCase()).doesNotContain("secret");

        JsonNode root = new ObjectMapper().readTree(body);
        assertThat(root.isArray()).isTrue();
        assertThat(root).hasSize(1);
        JsonNode item = root.get(0);
        assertThat(fieldNames(item)).containsExactlyInAnyOrder("kid", "publicKey");
        assertThat(item.get("kid").asText()).isEqualTo(issued.kid());
        assertNoFieldMentionsPrivateKey(root);
    }

    private UUID seedNeighborhood() {
        UUID nid = UUID.randomUUID();
        jdbc.sql("INSERT INTO neighborhoods (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", nid)
                .param("name", "Barrio Test")
                .param("slug", "barrio-" + nid)
                .update();
        return nid;
    }

    private static List<String> fieldNames(JsonNode obj) {
        return java.util.stream.StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(obj.fieldNames(), 0), false)
                .toList();
    }

    private static void assertNoFieldMentionsPrivateKey(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                assertThat(entry.getKey().toLowerCase())
                        .as("campo sospechoso: %s", entry.getKey())
                        .doesNotContain("private")
                        .doesNotContain("secret")
                        .doesNotContain("seed");
                assertNoFieldMentionsPrivateKey(entry.getValue());
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) assertNoFieldMentionsPrivateKey(child);
        }
    }
}
