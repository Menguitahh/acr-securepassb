package com.securepass.modules.access.infrastructure.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.securepass.modules.access.application.EnrollmentResult;

import java.time.Instant;
import java.util.UUID;

/**
 * Shape aditivo por {@code subjectKind} (WBS-122 / #175):
 *
 * <ul>
 *   <li>{@code subjectKind}: nunca null. Enum "GUEST" | "WORKER". Es el campo
 *       de ramificacion del cliente; si faltara seria un error de contrato,
 *       no un caso silencioso. Invariante del constructor.</li>
 *   <li>{@code guest}: presente solo cuando {@code subjectKind == "GUEST"}.
 *       Shape historico {@code {name, document}}.</li>
 *   <li>{@code worker}: presente solo cuando {@code subjectKind == "WORKER"}.
 *       Shape nuevo {@code {userId, name, document}}. {@code userId} puede
 *       venir null en respuestas reconstruidas desde AccessLog (retry con
 *       mismo clientEventId): el log no lo persiste. En el primer scan online
 *       siempre es no-null.</li>
 * </ul>
 *
 * <p>Consistente con {@code ValidateAccessResponse}, que ya usa el mismo
 * patron ({@code subjectKind + guest | worker}). No rompe el contrato del
 * invitado: la clave {@code guest} sigue con el mismo shape que antes de este
 * PR; solo cambia el caso worker, que hasta ahora rellenaba {@code guest} con
 * datos del trabajador — shape confuso que el front todavia no consumia.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnrollResponse(
        String result,
        String reason,
        Instant enrolledAt,
        UUID enrollmentId,
        String subjectKind,
        Guest guest,
        Worker worker
) {

    public EnrollResponse {
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("result requerido");
        }
        if (subjectKind == null) {
            throw new IllegalArgumentException("subjectKind requerido");
        }
        if (!"GUEST".equals(subjectKind) && !"WORKER".equals(subjectKind)) {
            throw new IllegalArgumentException("subjectKind invalido: " + subjectKind);
        }
        if ("GUEST".equals(subjectKind)) {
            if (guest == null) throw new IllegalArgumentException("guest requerido para subjectKind=GUEST");
            if (worker != null) throw new IllegalArgumentException("worker no aplica a GUEST");
        } else {
            if (worker == null) throw new IllegalArgumentException("worker requerido para subjectKind=WORKER");
            if (guest != null) throw new IllegalArgumentException("guest no aplica a WORKER");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Guest(String name, String document) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Worker(UUID userId, String name, String document) {}

    public static EnrollResponse from(EnrollmentResult r) {
        if (r instanceof EnrollmentResult.Enrolled ok) {
            return switch (ok.subjectKind()) {
                case GUEST -> new EnrollResponse(
                        "ALLOWED", null, ok.enrollment().enrolledAt(), ok.enrollment().id(),
                        "GUEST",
                        new Guest(ok.subjectName(), ok.enrollment().documentNumber()),
                        null);
                case WORKER -> new EnrollResponse(
                        "ALLOWED", null, ok.enrollment().enrolledAt(), ok.enrollment().id(),
                        "WORKER",
                        null,
                        new Worker(ok.workerUserId(), ok.subjectName(), ok.enrollment().documentNumber()));
            };
        }
        EnrollmentResult.Denied denied = (EnrollmentResult.Denied) r;
        return switch (denied.subjectKind()) {
            case GUEST -> new EnrollResponse(
                    "DENIED", denied.reason().name(), null, null,
                    "GUEST",
                    new Guest(null, denied.log().subjectDocument()),
                    null);
            case WORKER -> new EnrollResponse(
                    "DENIED", denied.reason().name(), null, null,
                    "WORKER",
                    null,
                    new Worker(denied.workerUserId(), null, denied.log().subjectDocument()));
        };
    }
}
