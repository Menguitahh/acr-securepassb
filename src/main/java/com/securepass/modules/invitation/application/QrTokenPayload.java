package com.securepass.modules.invitation.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

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
        @JsonProperty("w") List<Integer> weekdays,
        @JsonProperty("df") String dailyFrom,
        @JsonProperty("du") String dailyUntil
) {
    private static final Pattern HHMM = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d$");

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
