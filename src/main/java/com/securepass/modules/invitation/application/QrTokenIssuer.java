package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.AesGcmCipher;
import com.securepass.shared.crypto.Ed25519Signer;
import com.securepass.shared.exception.NeighborhoodNotProvisionedException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Service
public class QrTokenIssuer {

    static final int MAX_PAYLOAD_BYTES = 300;
    private static final int PAYLOAD_VERSION = 1;

    private final SigningKeyRepository keys;
    private final AesGcmCipher cipher;
    private final QrTokenCodec codec;

    public QrTokenIssuer(SigningKeyRepository keys, AesGcmCipher cipher, QrTokenCodec codec) {
        this.keys = keys;
        this.cipher = cipher;
        this.codec = codec;
    }

    public String issue(IssueCommand cmd) {
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");
        SigningKey key = keys.findActive(cmd.neighborhoodId())
                .orElseThrow(() -> new NeighborhoodNotProvisionedException(
                        cmd.neighborhoodId(), "signing key activa"));

        boolean recurring = cmd.type() == InvitationType.RECURRING;
        List<Integer> weekdays = recurring ? validateWeekdays(cmd.weekdays()) : null;
        String dailyFrom = recurring ? formatHhmm(cmd.dailyFrom(), "dailyFrom") : null;
        String dailyUntil = recurring ? formatHhmm(cmd.dailyUntil(), "dailyUntil") : null;

        QrTokenPayload payload = new QrTokenPayload(
                PAYLOAD_VERSION, key.kid(), cmd.jti(),
                cmd.neighborhoodId(), cmd.lotId(), cmd.invitationId(),
                cmd.type().code(),
                cmd.validFrom().getEpochSecond(), cmd.validUntil().getEpochSecond(),
                cmd.maxUses(),
                weekdays, dailyFrom, dailyUntil);

        byte[] payloadBytes = codec.serialize(payload);
        if (payloadBytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException(
                    "payload excede " + MAX_PAYLOAD_BYTES + " bytes: " + payloadBytes.length);
        }

        byte[] seed = cipher.decrypt(key.privateKeyEnc());
        byte[] signature = Ed25519Signer.sign(seed, payloadBytes);
        return codec.encode(payload, signature);
    }

    private static List<Integer> validateWeekdays(List<Integer> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            throw new IllegalArgumentException("RECURRING requiere weekdays");
        }
        for (Integer d : weekdays) {
            if (d == null || d < 1 || d > 7) throw new IllegalArgumentException("weekday fuera de 1..7");
        }
        return List.copyOf(weekdays);
    }

    private static String formatHhmm(LocalTime t, String name) {
        if (t == null) throw new IllegalArgumentException("RECURRING requiere " + name);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    public record IssueCommand(
            UUID neighborhoodId,
            UUID lotId,
            UUID invitationId,
            UUID jti,
            InvitationType type,
            Instant validFrom,
            Instant validUntil,
            int maxUses,
            List<Integer> weekdays,
            LocalTime dailyFrom,
            LocalTime dailyUntil
    ) {
        public IssueCommand {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (lotId == null) throw new IllegalArgumentException("lotId requerido");
            if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");
            if (jti == null) throw new IllegalArgumentException("jti requerido");
            if (type == null) throw new IllegalArgumentException("type requerido");
            if (validFrom == null || validUntil == null) throw new IllegalArgumentException("ventana requerida");
            if (!validUntil.isAfter(validFrom)) throw new IllegalArgumentException("validUntil > validFrom");
            if (maxUses <= 0) throw new IllegalArgumentException("maxUses > 0");
        }
    }
}
