package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regresion end-to-end DB -> dominio: mapUser tiene que poder construir un
 * User a partir de rows con password_hash IS NULL en los tres estados que el
 * invariante ahora permite (PENDING_ACTIVATION, SUSPENDED, DELETED).
 *
 * El bug original: el invariante exigia password_hash != null salvo en
 * PENDING_ACTIVATION. Un row SUSPENDED / DELETED con password_hash NULL en
 * DB (como los que produce el overlay dev o un soft-delete real) hacia
 * reventar el constructor y el error se filtraba hasta el @ExceptionHandler
 * generico como 500 "Error interno". Los unit tests del use case pasaban
 * porque mockeaban el repo con new User(..., "hash", ..., SUSPENDED); nunca
 * ejercitaban el mapeo desde DB.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserJdbcRepositoryIT {

    @Autowired UserRepository repo;
    @Autowired JdbcTemplate jdbc;

    @Test
    void mapeaUsersConPasswordHashNullEnLosTresEstadosNoActive() {
        UUID pendingId = insertRawUser("pending-" + UUID.randomUUID() + "@t.com",
                "PENDING_ACTIVATION", null, "77000001");
        UUID suspendedId = insertRawUser("suspended-" + UUID.randomUUID() + "@t.com",
                "SUSPENDED", null, "77000002");
        UUID deletedId = insertRawUser("deleted-" + UUID.randomUUID() + "@t.com",
                "DELETED", null, "77000003");

        Optional<User> pending = repo.findById(pendingId);
        assertThat(pending).isPresent();
        assertThat(pending.get().status()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(pending.get().passwordHash()).isNull();

        Optional<User> suspended = repo.findByDocument("77000002");
        assertThat(suspended).isPresent();
        assertThat(suspended.get().status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(suspended.get().passwordHash()).isNull();

        Optional<User> deleted = repo.findByDocument("77000003");
        assertThat(deleted).isPresent();
        assertThat(deleted.get().status()).isEqualTo(UserStatus.DELETED);
        assertThat(deleted.get().passwordHash()).isNull();
    }

    @Test
    void findByDocumentDeSuspendedSinHashNoExplota() {
        // El happy-path del bug: AddFamilyMemberUseCase.add() empieza con
        // users.findByDocument(dni). Ese solo call es el que reventaba.
        insertRawUser("bug-" + UUID.randomUUID() + "@t.com", "SUSPENDED", null, "77999001");

        Optional<User> u = repo.findByDocument("77999001");
        assertThat(u).isPresent();
        assertThat(u.get().status()).isEqualTo(UserStatus.SUSPENDED);
    }

    private UUID insertRawUser(String email, String status, String passwordHash, String doc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, document_number, status)
                VALUES (?, ?, ?, 'Test User', ?, ?)
                """, id, email, passwordHash, doc, status);
        return id;
    }
}
