package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS-116: shape worker del payload SP1 y backwards compat con invitacion.
 */
class QrTokenPayloadWorkerTest {

    private final ObjectMapper mapper = new ObjectMapper();

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
        // No debe emitir los campos de invitacion (l, i, t, f, u, m, w, df, du).
        assertThat(json).doesNotContain("\"l\":", "\"i\":", "\"t\":", "\"f\":",
                "\"u\":", "\"m\":", "\"w\":", "\"df\":", "\"du\":");
        assertThat(json).contains("\"st\":\"W\"", "\"s\":");
    }

    @Test
    void workerSubRequerido() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, "W", null,
                null, null, null, null, null, null, null, null, null))
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
                UUID.randomUUID(), null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no aplican");
    }

    @Test
    void subTypeDesconocidoRechaza() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, "X", null,
                null, null, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subType desconocido");
    }

    @Test
    void invitacionSigueRequerida() {
        UUID jti = UUID.randomUUID();
        UUID nbh = UUID.randomUUID();
        // Sin subType: forma invitacion. Falta invitationId -> rechaza.
        assertThatThrownBy(() -> new QrTokenPayload(
                1, "kid-1", jti, nbh, null, null,
                UUID.randomUUID(), null, "S", 1L, 2L, 1, null, null, null))
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
                "R", 100L, 200L, 5,
                List.of(1, 3, 5), "08:00", "12:00");
        assertThat(p.subType()).isNull();
        assertThat(p.type()).isEqualTo("R");
    }

    @Test
    void invitacionJsonNoEmiteCamposDelWorker() throws Exception {
        QrTokenPayload p = new QrTokenPayload(
                1, "kid-1", UUID.randomUUID(), UUID.randomUUID(), null, null,
                UUID.randomUUID(), UUID.randomUUID(),
                "S", 1L, 2L, 1, null, null, null);
        String json = mapper.writeValueAsString(p);
        assertThat(json).doesNotContain("\"st\":", "\"s\":");
        assertThat(json).contains("\"i\":", "\"l\":", "\"t\":\"S\"");
    }
}
