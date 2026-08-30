package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Payload SP1. Dos formas:
 *
 * <ol>
 *   <li><b>Invitacion</b> (default, {@code subType=null}): forma historica.
 *       Requiere invitationId, lotId, type, validFrom/Until, maxUses.
 *       Si type=R exige weekdays + dailyFrom + dailyUntil.</li>
 *   <li><b>Worker</b> ({@code subType="W"}, WBS-116): QR permanente ligado
 *       a un usuario. Requiere sub (userId). Sin invitationId, sin lotId
 *       (el trabajador puede tener varios), sin ventana ni cupo — el
 *       permiso vive en {@code lot_permissions} y validate lo evalua contra
 *       la franja del server (WBS-117).</li>
 * </ol>
 *
 * <p>Compat: los campos de invitacion son wrappers ({@code Long}, {@code Integer})
 * para poder ausentarse en el shape worker. Con {@code @JsonInclude.NON_NULL}
 * el JSON de un payload invitacion es identico al v1: no cambia el tamano ni
 * la firma de tokens ya emitidos.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QrTokenPayload(
        @JsonProperty("v") int version,
        @JsonProperty("k") String kid,
        @JsonProperty("j") UUID jti,
        @JsonProperty("n") UUID neighborhoodId,
        @JsonProperty("st") String subType,
        @JsonProperty("s") UUID sub,
        @JsonProperty("l") UUID lotId,
        @JsonProperty("i") UUID invitationId,
        @JsonProperty("t") String type,
        @JsonProperty("f") Long validFrom,
        @JsonProperty("u") Long validUntil,
        @JsonProperty("m") Integer maxUses,
        @JsonProperty("w") List<Integer> weekdays,
        @JsonProperty("df") String dailyFrom,
        @JsonProperty("du") String dailyUntil
) {
    public static final String SUBTYPE_WORKER = "W";
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

    public QrTokenPayload {
        if (version <= 0) throw new IllegalArgumentException("version debe ser > 0");
        if (kid == null || kid.isBlank()) throw new IllegalArgumentException("kid requerido");
        if (jti == null) throw new IllegalArgumentException("jti requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");

        boolean isWorker = SUBTYPE_WORKER.equals(subType);
        if (subType != null && !isWorker) {
            throw new IllegalArgumentException("subType desconocido: " + subType);
        }

        if (isWorker) {
            if (sub == null) throw new IllegalArgumentException("sub requerido para subType=W");
            if (invitationId != null || lotId != null || type != null
                    || validFrom != null || validUntil != null || maxUses != null
                    || weekdays != null || dailyFrom != null || dailyUntil != null) {
                throw new IllegalArgumentException(
                        "subType=W solo lleva jti/kid/n/s; los campos de invitacion no aplican");
            }
        } else {
            // Invitacion (subType == null): shape historico obligatorio.
            if (lotId == null) throw new IllegalArgumentException("lotId requerido");
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            if (type == null || type.length() != 1) throw new IllegalArgumentException("type debe ser 1 caracter");
            if (validFrom == null || validUntil == null) {
                throw new IllegalArgumentException("validFrom y validUntil requeridos");
            }
            if (validUntil <= validFrom) throw new IllegalArgumentException("validUntil > validFrom");
            if (maxUses == null || maxUses <= 0) throw new IllegalArgumentException("maxUses > 0");
            if (sub != null) throw new IllegalArgumentException("sub solo aplica con subType");

            boolean recurring = "R".equals(type);
            boolean anyRecurringField = weekdays != null || dailyFrom != null || dailyUntil != null;
            if (recurring) {
                if (weekdays == null || weekdays.isEmpty() || dailyFrom == null || dailyUntil == null) {
                    throw new IllegalArgumentException("RECURRING requiere w, df y du");
                }
                for (Integer d : weekdays) {
                    if (d == null || d < 1 || d > 7) throw new IllegalArgumentException("weekday fuera de 1..7");
                }
                if (!HHMM.matcher(dailyFrom).matches() || !HHMM.matcher(dailyUntil).matches()) {
                    throw new IllegalArgumentException("df/du deben ser HH:mm");
                }
                if (dailyUntil.compareTo(dailyFrom) <= 0) {
                    throw new IllegalArgumentException("du debe ser posterior a df");
                }
                weekdays = List.copyOf(weekdays);
            } else if (anyRecurringField) {
                throw new IllegalArgumentException("w, df y du solo aplican a tipo R");
            }
        }
    }

    public static QrTokenPayload worker(int version, String kid, UUID jti,
                                        UUID neighborhoodId, UUID userId) {
        return new QrTokenPayload(
                version, kid, jti, neighborhoodId,
                SUBTYPE_WORKER, userId,
                null, null, null, null, null, null,
                null, null, null);
    }
}
