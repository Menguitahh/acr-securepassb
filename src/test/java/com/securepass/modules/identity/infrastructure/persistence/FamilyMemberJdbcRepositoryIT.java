package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.domain.FamilyMember;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regresion: el alta con relation=null pegaba PSQLException
 * "could not determine data type of parameter $5" porque el placeholder de
 * :relation dentro de jsonb_build_object no tiene contexto de tipo. El fix es
 * el cast ::text en el SQL. Contra Postgres real (H2 no reproduce).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class FamilyMemberJdbcRepositoryIT {

    @Autowired FamilyMemberJdbcRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void saveConRelationNull_noRevienta_yPersisteAttributesConRelationNull() {
        UUID neighborhoodId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO neighborhoods (id, name, slug, timezone)
                VALUES (?, ?, ?, 'America/Argentina/Mendoza')
                """, neighborhoodId, "Barrio " + neighborhoodId, "b-" + neighborhoodId.toString().substring(0, 8));

        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, full_name, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, userId, userId + "@t.com", "Test User");

        UUID lotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO lots (id, neighborhood_id, code, street, number, status)
                VALUES (?, ?, ?, 'Calle', '1', 'ACTIVE')
                """, lotId, neighborhoodId, "L-" + lotId.toString().substring(0, 6));

        repo.save(new FamilyMember(userId, lotId, null, false, false, true));

        String relation = jdbc.queryForObject(
                "SELECT attributes->>'relation' FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                String.class, userId, lotId);
        assertThat(relation).isNull();
    }
}
