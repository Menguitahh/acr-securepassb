package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Payload SP1. Dos formas:
 *
 * <ol>
 *   <li><b>Invitacion</b> (default, {@code subType=null}): forma historica.
 *       Requiere invitationId, lotId, type, validFrom/Until, maxUses.</li>
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
 *
 * <p><b>WBS-127 — tolerancia PERMANENTE al valor legacy {@code type="R"}.</b>
 * Antes de WBS-127 el tipo {@code RECURRING} existia. El pase permanente del
 * residente se emitia con {@code type="R"} y {@code valid_until = FAR_FUTURE},
 * o sea que esos QR <b>no caducan nunca</b>. La migracion V17 preserva la
 * fila del pase in-place (mismo id, mismo jti, type flip a EVENT), pero los
 * bytes del token firmado que viven en los telefonos siguen diciendo
 * {@code "t":"R"} para siempre. Este constructor acepta cualquier string de
 * 1 caracter en {@code type} para que esos tokens sigan parseando; validate
 * usa el tipo real de la fila en DB, no el del payload.
 *
 * <p>Los campos {@code w/df/du} desaparecieron del record. Jackson en Spring
 * Boot tiene {@code FAIL_ON_UNKNOWN_PROPERTIES=false} por defecto, asi que
 * llegan al parser y se descartan silenciosamente. Confirmado con test
 * {@code QrTokenPayloadWorkerTest.tokenPreV17ConTypeRParseaYIgnoraCamposDeFranja}.
 *
 * <p><b>No "limpiar" esta tolerancia.</b> No tiene fecha de vencimiento. No
 * es un ponytail transitorio. Es una condicion del formato heredado que
 * vive mientras existan QR pre-V17 en dispositivos, o sea indefinidamente.
 * Sacar el chequeo tolerante rompe el QR de todos los residentes que no
 * hayan reabierto la app despues de la migracion (que no tienen porque
 * hacerlo — el QR viejo funciona).
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
        @JsonProperty("m") Integer maxUses
) {
    public static final String SUBTYPE_WORKER = "W";

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
                    || validFrom != null || validUntil != null || maxUses != null) {
                throw new IllegalArgumentException(
                        "subType=W solo lleva jti/kid/n/s; los campos de invitacion no aplican");
            }
        } else {
            // Invitacion (subType == null): shape historico obligatorio.
            if (lotId == null) throw new IllegalArgumentException("lotId requerido");
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            // WBS-127: type sigue siendo un string de 1 char, sin restringir el
            // valor. Los tokens pre-V17 emitidos con "R" tienen que seguir
            // parseando (ver JavaDoc del record). El tipo real del acceso sale
            // de la fila en DB via invitationId, no del payload.
            if (type == null || type.length() != 1) throw new IllegalArgumentException("type debe ser 1 caracter");
            if (validFrom == null || validUntil == null) {
                throw new IllegalArgumentException("validFrom y validUntil requeridos");
            }
            if (validUntil <= validFrom) throw new IllegalArgumentException("validUntil > validFrom");
            if (maxUses == null || maxUses <= 0) throw new IllegalArgumentException("maxUses > 0");
            if (sub != null) throw new IllegalArgumentException("sub solo aplica con subType");
        }
    }

    public static QrTokenPayload worker(int version, String kid, UUID jti,
                                        UUID neighborhoodId, UUID userId) {
        return new QrTokenPayload(
                version, kid, jti, neighborhoodId,
                SUBTYPE_WORKER, userId,
                null, null, null, null, null, null);
    }
}
