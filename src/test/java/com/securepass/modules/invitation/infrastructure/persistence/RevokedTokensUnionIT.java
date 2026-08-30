package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.invitation.application.RevokedEntry;
import com.securepass.modules.invitation.application.RevokedTokensQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-117: regresion del bug de paginacion en /access/revoked.
 *
 * <p>Setup: MAX_LIMIT+50 invitaciones revocadas + 5 worker_tokens revocados
 * con revoked_at MAYOR que cualquier invitacion. Con la impl vieja (LIMIT por
 * fuente + combine + cut) los worker_tokens caian fuera del corte inicial y
 * el cursor client-side los saltaba. Con la impl union + LIMIT unico, el
 * client los recibe en una llamada posterior recorriendo el cursor.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RevokedTokensUnionIT {

    static final int LIMIT = 1000;

    @Autowired JdbcTemplate jdbc;
    @Autowired RevokedTokensQuery query;

    UUID nbh;
    UUID owner;

    @BeforeEach
    void seed() {
        nbh = UUID.randomUUID();
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhoods (id, name, slug) VALUES (?, 'B', ?)",
                nbh, "b-" + UUID.randomUUID());
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'o', 'ACTIVE')",
                owner, "o-" + owner + "@t.com");
        // lot para FK de invitations
        UUID lot = UUID.randomUUID();
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                lot, nbh, "L-" + UUID.randomUUID().toString().substring(0, 6));

        // 1050 invitaciones revocadas, revoked_at desde T=100 hasta T=1149 (1s cada una).
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        IntStream.range(0, 1050).forEach(i -> {
            UUID id = UUID.randomUUID();
            UUID jti = UUID.randomUUID();
            Instant validFrom = base.plusSeconds(0);
            Instant validUntil = base.plusSeconds(3600);
            Instant revokedAt = base.plusSeconds(100 + i);
            jdbc.update("""
                    INSERT INTO invitations (
                      id, neighborhood_id, lot_id, created_by, type, status,
                      guest_name, guest_document, companions,
                      valid_from, valid_until, max_uses, used_count,
                      token_kid, revoked_at, jti)
                    VALUES (?, ?, ?, ?, 'SINGLE', 'REVOKED',
                            'g', '30000001', 0,
                            ?, ?, 1, 0,
                            'kid-1', ?, ?)
                    """,
                    id, nbh, lot, owner,
                    Timestamp.from(validFrom), Timestamp.from(validUntil),
                    Timestamp.from(revokedAt), jti);
        });

        // 5 worker_tokens revocados con revoked_at POSTERIOR a todos los de invitations.
        Instant workersRevokedBase = base.plusSeconds(2000);
        IntStream.range(0, 5).forEach(i -> {
            UUID wUser = UUID.randomUUID();
            jdbc.update("INSERT INTO users (id, email, password_hash, full_name, status) VALUES (?, ?, 'h', 'w', 'ACTIVE')",
                    wUser, "w-" + wUser + "@t.com");
            jdbc.update("""
                    INSERT INTO worker_tokens (user_id, neighborhood_id, jti, issued_at, revoked_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    wUser, nbh, UUID.randomUUID(),
                    Timestamp.from(base), Timestamp.from(workersRevokedBase.plusSeconds(i)));
        });
    }

    @Test
    void primeraPaginaMezclaAmbasFuentesOrdenadasPorRevokedAt() {
        List<RevokedEntry> first = query.listRevoked(nbh, null, LIMIT);
        assertThat(first).hasSize(LIMIT);
        // Todas las revoked_at deben estar en orden ascendente.
        for (int i = 1; i < first.size(); i++) {
            assertThat(first.get(i).revokedAt())
                    .isAfterOrEqualTo(first.get(i - 1).revokedAt());
        }
        // Los worker_tokens (los mas nuevos) no entran en la primera pagina.
        // Verificamos que la ultima revoked_at es antes de los worker_tokens.
        Instant cursor = first.get(first.size() - 1).revokedAt();
        assertThat(cursor).isBefore(Instant.parse("2026-01-01T00:33:20Z")); // base + 2000s
    }

    @Test
    void segundaPaginaConCursorRecuperaLosWorkerTokensRestantes() {
        List<RevokedEntry> first = query.listRevoked(nbh, null, LIMIT);
        Instant cursor = first.get(first.size() - 1).revokedAt();

        List<RevokedEntry> second = query.listRevoked(nbh, cursor, LIMIT);
        // 1050 invitations + 5 workers = 1055 total. Primera pagina se llevo 1000.
        // Segunda pagina: los 50 invitations + 5 workers restantes = 55.
        assertThat(second).hasSize(55);
        // Los ultimos 5 deben ser los worker_tokens (revoked_at mas grandes).
        Instant workersStart = Instant.parse("2026-01-01T00:33:20Z");
        long fromWorkers = second.stream()
                .filter(e -> !e.revokedAt().isBefore(workersStart))
                .count();
        assertThat(fromWorkers).isEqualTo(5);
    }
}
