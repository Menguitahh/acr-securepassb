package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securepass.modules.access.application.EnrollmentResult;
import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.GuestEnrollment;
import com.securepass.modules.access.domain.SubjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS-122 / #175: contrato aditivo del EnrollResponse. Se testea el shape
 * JSON exacto de las cuatro combinaciones (GUEST/WORKER × ALLOWED/DENIED)
 * mas las invariantes del constructor.
 */
class EnrollResponseTest {

    private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AccessLog log(SubjectType type, boolean allowed, DenialReason reason, String document) {
        return new AccessLog(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, null,
                Direction.IN, type, allowed, reason,
                null, document, 0,
                null, T0, false, false, UUID.randomUUID());
    }

    private GuestEnrollment enrollment(String document) {
        return new GuestEnrollment(
                UUID.randomUUID(), UUID.randomUUID(), null, document, T0,
                UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void guestAllowedSerializaConGuestYSinWorker() throws Exception {
        GuestEnrollment e = enrollment("30123456");
        AccessLog l = log(SubjectType.GUEST, true, null, "30123456");
        EnrollResponse resp = EnrollResponse.from(EnrollmentResult.Enrolled.guest(e, l, "Maria Lopez"));

        assertThat(resp.result()).isEqualTo("ALLOWED");
        assertThat(resp.subjectKind()).isEqualTo("GUEST");
        assertThat(resp.guest()).isNotNull();
        assertThat(resp.guest().name()).isEqualTo("Maria Lopez");
        assertThat(resp.guest().document()).isEqualTo("30123456");
        assertThat(resp.worker()).isNull();

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"subjectKind\":\"GUEST\"");
        assertThat(json).contains("\"guest\":{");
        assertThat(json).doesNotContain("\"worker\"");
    }

    @Test
    void workerAllowedSerializaConWorkerYSinGuest() throws Exception {
        UUID userId = UUID.randomUUID();
        GuestEnrollment e = enrollment("30999001");
        AccessLog l = log(SubjectType.SERVICE, true, null, "30999001");
        EnrollResponse resp = EnrollResponse.from(
                EnrollmentResult.Enrolled.worker(userId, e, l, "Juan Jardinero"));

        assertThat(resp.result()).isEqualTo("ALLOWED");
        assertThat(resp.subjectKind()).isEqualTo("WORKER");
        assertThat(resp.worker()).isNotNull();
        assertThat(resp.worker().userId()).isEqualTo(userId);
        assertThat(resp.worker().name()).isEqualTo("Juan Jardinero");
        assertThat(resp.worker().document()).isEqualTo("30999001");
        assertThat(resp.guest()).isNull();

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"subjectKind\":\"WORKER\"");
        assertThat(json).contains("\"worker\":{");
        assertThat(json).contains("\"userId\":\"" + userId + "\"");
        assertThat(json).doesNotContain("\"guest\"");
    }

    @Test
    void guestDeniedSerializaConGuestDocumentoSinNombre() throws Exception {
        AccessLog l = log(SubjectType.GUEST, false, DenialReason.DOCUMENT_MISMATCH, "99999999");
        EnrollResponse resp = EnrollResponse.from(
                EnrollmentResult.Denied.guest(DenialReason.DOCUMENT_MISMATCH, l));

        assertThat(resp.result()).isEqualTo("DENIED");
        assertThat(resp.reason()).isEqualTo("DOCUMENT_MISMATCH");
        assertThat(resp.subjectKind()).isEqualTo("GUEST");
        assertThat(resp.guest().name()).isNull();
        assertThat(resp.guest().document()).isEqualTo("99999999");
        assertThat(resp.worker()).isNull();

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"subjectKind\":\"GUEST\"");
        assertThat(json).doesNotContain("\"worker\"");
    }

    @Test
    void workerDeniedSerializaConWorkerUserIdYDocumento() throws Exception {
        UUID userId = UUID.randomUUID();
        AccessLog l = log(SubjectType.SERVICE, false, DenialReason.DOCUMENT_MISMATCH, "99999999");
        EnrollResponse resp = EnrollResponse.from(
                EnrollmentResult.Denied.worker(userId, DenialReason.DOCUMENT_MISMATCH, l));

        assertThat(resp.subjectKind()).isEqualTo("WORKER");
        assertThat(resp.worker().userId()).isEqualTo(userId);
        assertThat(resp.worker().name()).isNull();
        assertThat(resp.worker().document()).isEqualTo("99999999");
        assertThat(resp.guest()).isNull();

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"subjectKind\":\"WORKER\"");
        assertThat(json).doesNotContain("\"guest\"");
    }

    @Test
    void workerDeniedEnRetrySinUserIdSerializaSinCampoUserId() throws Exception {
        // reconstruct desde AccessLog SERVICE denegado: workerUserId es null.
        AccessLog l = log(SubjectType.SERVICE, false, DenialReason.REVOKED, "30999001");
        EnrollResponse resp = EnrollResponse.from(
                EnrollmentResult.Denied.worker(null, DenialReason.REVOKED, l));

        assertThat(resp.subjectKind()).isEqualTo("WORKER");
        assertThat(resp.worker()).isNotNull();
        assertThat(resp.worker().userId()).isNull();
        assertThat(resp.worker().document()).isEqualTo("30999001");

        String json = mapper.writeValueAsString(resp);
        assertThat(json).contains("\"subjectKind\":\"WORKER\"");
        assertThat(json).doesNotContain("\"userId\"");
    }

    // -----------------------------------------------------------------
    // Invariantes: subjectKind nunca null, xor de guest/worker segun kind.
    // -----------------------------------------------------------------

    @Test
    void constructorRechazaSubjectKindNull() {
        assertThatThrownBy(() -> new EnrollResponse(
                "ALLOWED", null, T0, UUID.randomUUID(), null,
                new EnrollResponse.Guest("N", "1"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subjectKind requerido");
    }

    @Test
    void constructorRechazaSubjectKindInvalido() {
        assertThatThrownBy(() -> new EnrollResponse(
                "ALLOWED", null, T0, UUID.randomUUID(), "RESIDENT",
                new EnrollResponse.Guest("N", "1"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subjectKind invalido");
    }

    @Test
    void constructorRechazaGuestConWorkerAmbosPresentes() {
        assertThatThrownBy(() -> new EnrollResponse(
                "ALLOWED", null, T0, UUID.randomUUID(), "GUEST",
                new EnrollResponse.Guest("N", "1"),
                new EnrollResponse.Worker(UUID.randomUUID(), "N", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker no aplica a GUEST");
    }

    @Test
    void constructorRechazaWorkerConGuestAmbosPresentes() {
        assertThatThrownBy(() -> new EnrollResponse(
                "ALLOWED", null, T0, UUID.randomUUID(), "WORKER",
                new EnrollResponse.Guest("N", "1"),
                new EnrollResponse.Worker(UUID.randomUUID(), "N", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guest no aplica a WORKER");
    }

    @Test
    void constructorRechazaGuestConGuestNull() {
        assertThatThrownBy(() -> new EnrollResponse(
                "ALLOWED", null, T0, UUID.randomUUID(), "GUEST", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guest requerido");
    }

    @Test
    void constructorRechazaWorkerConWorkerNull() {
        assertThatThrownBy(() -> new EnrollResponse(
                "ALLOWED", null, T0, UUID.randomUUID(), "WORKER", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("worker requerido");
    }
}
