package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record QrTokenPayload(
        @JsonProperty("v") int version,
        @JsonProperty("k") String kid,
        @JsonProperty("j") UUID jti,
        @JsonProperty("n") UUID neighborhoodId,
        @JsonProperty("l") UUID lotId,
        @JsonProperty("i") UUID invitationId,
        @JsonProperty("t") String type,
        @JsonProperty("f") long validFrom,
        @JsonProperty("u") long validUntil,
        @JsonProperty("m") int maxUses,
        @JsonProperty("w") Integer weekdaysMask,
        @JsonProperty("df") Integer dailyFromMin,
        @JsonProperty("du") Integer dailyUntilMin
) {
    public QrTokenPayload {
        if (version <= 0) throw new IllegalArgumentException("version debe ser > 0");
        if (kid == null || kid.isBlank()) throw new IllegalArgumentException("kid requerido");
        if (jti == null) throw new IllegalArgumentException("jti requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
        if (type == null || type.length() != 1) throw new IllegalArgumentException("type debe ser 1 caracter");
        if (validUntil <= validFrom) throw new IllegalArgumentException("validUntil > validFrom");
        if (maxUses <= 0) throw new IllegalArgumentException("maxUses > 0");
        boolean recurring = "R".equals(type);
        boolean anyRecurringField = weekdaysMask != null || dailyFromMin != null || dailyUntilMin != null;
        if (recurring) {
            if (weekdaysMask == null || dailyFromMin == null || dailyUntilMin == null) {
                throw new IllegalArgumentException("RECURRING requiere w, df y du");
            }
            if (weekdaysMask <= 0 || weekdaysMask > 0b1111111) {
                throw new IllegalArgumentException("w debe ser bitmask 1..127");
            }
            if (dailyFromMin < 0 || dailyUntilMin > 1440 || dailyUntilMin <= dailyFromMin) {
                throw new IllegalArgumentException("df/du fuera de rango o invertidos");
            }
        } else if (anyRecurringField) {
            throw new IllegalArgumentException("w, df y du solo aplican a tipo R");
        }
    }
}
