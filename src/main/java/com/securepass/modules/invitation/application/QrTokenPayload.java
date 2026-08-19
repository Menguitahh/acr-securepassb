package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

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
        @JsonProperty("m") int maxUses
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
    }
}
