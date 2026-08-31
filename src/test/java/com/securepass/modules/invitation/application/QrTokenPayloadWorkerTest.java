package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS-116: shape worker del payload SP1 y backwards compat con invitacion.
 */
class QrTokenPayloadWorkerTest {

    // Spring Boot default: FAIL_ON_UNKNOWN_PROPERTIES=false. Replicamos aca
    // para blindar la tolerancia legacy (ver JavaDoc de QrTokenPayload).
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void workerHelperConstruyeShapeMinimo() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        QrTokenPayload p = QrTokenPayload.worker(1, "kid-1", jti, nbh, user);

        assertThat(p.subType()).isEqualTo("W");
        assertThat(p.sub()).isEqualTo(user);
        assertThat(p.invitationId()).isNull();
        assertThat(p.lotId()).isNull();
        assertThat(p.type()).isNull();
        assertThat(p.validFrom()).isNull();
        assertThat(p.maxUses()).isNull();
    }

    @Test
    void workerSerializaSinCamposDeInvitacion() throws Exception {
        QrTokenPayload p = QrTokenPayload.worker(
                1, "kid-1", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String json = mapper.writeValueAsString(p);
        assertThat(json).doesNotContain("\"l\":", "\"i\":", "\"t\":", "\"f\":",
                "\"u\":", "\"m\":");
        assertThat(json).contains("\"st\":\"W\"", "\"s\":");
    }

    @Test
    void workerSubRequerido() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, "W", null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sub requerido");
    }

    @Test
    void workerRechazaCamposDeInvitacion() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, "W", user,
                UUID.randomUUID(), null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no aplican");
    }

    @Test
    void subTypeDesconocidoRechaza() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, "X", null,
                null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subType desconocido");
    }

    @Test
    void invitacionSigueRequerida() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, null, null,
                UUID.randomUUID(), null, "S", 1L, 2L, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invitationId requerido");
    }

    @Test
    void invitacionShapeCompletoValida() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        QrTokenPayload p = new QrTokenPayload(
                1, "kid-1", jti, nbh, null, null,
                UUID.randomUUID(), UUID.randomUUID(),
                "E", 100L, 200L, 5);
        assertThat(p.subType()).isNull();
        assertThat(p.type()).isEqualTo("E");
    }

    @Test
    void invitacionJsonNoEmiteCamposDelWorker() throws Exception {
        QrTokenPayload p = new QrTokenPayload(
                1, "kid-1", UUID.randomUUID(), UUID.randomUUID(), null, null,
                UUID.randomUUID(), UUID.randomUUID(),
                "S", 1L, 2L, 1);
        String json = mapper.writeValueAsString(p);
        assertThat(json).doesNotContain("\"st\":", "\"s\":");
        assertThat(json).contains("\"i\":", "\"l\":", "\"t\":\"S\"");
    }

    // WBS-127: tolerancia PERMANENTE al valor legacy type="R" y a campos w/df/du
    // en payloads pre-V17. El constructor acepta "R"; Jackson descarta w/df/du
    // silenciosamente (FAIL_ON_UNKNOWN_PROPERTIES=false por default en Spring
    // Boot). Sacar esta tolerancia rompe los QR permanentes de residentes que
    // no reabran la app tras la migracion.
    @Test
    void tokenPreV17ConTypeRParseaYIgnoraCamposDeFranja() throws Exception {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        UUID lot = UUID.randomUUID();
        UUID inv = UUID.randomUUID();
        String legacyJson = "{"
                + "\"v\":1,\"k\":\"kid-1\","
                + "\"j\":\"" + jti + "\",\"n\":\"" + nbh + "\","
                + "\"l\":\"" + lot + "\",\"i\":\"" + inv + "\","
                + "\"t\":\"R\",\"f\":1,\"u\":2,\"m\":1,"
                + "\"w\":[1,2,3],\"df\":\"08:00\",\"du\":\"18:00\""
                + "}";

        QrTokenPayload p = mapper.readValue(legacyJson, QrTokenPayload.class);

        assertThat(p.type()).isEqualTo("R");
        assertThat(p.invitationId()).isEqualTo(inv);
        assertThat(p.maxUses()).isEqualTo(1);
    }
}
