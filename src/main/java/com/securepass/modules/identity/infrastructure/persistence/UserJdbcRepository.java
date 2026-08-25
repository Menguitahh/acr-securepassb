package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserJdbcRepository implements UserRepository {

    private final JdbcClient jdbc;

    public UserJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findByIdentifier(String identifier) {
        return jdbc.sql("""
                SELECT id, email, phone, password_hash, full_name, document_number, status
                  FROM users
                 WHERE email = :id OR phone = :id
                 LIMIT 1
                """)
                .param("id", identifier)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, email, phone, password_hash, full_name, document_number, status
                  FROM users
                 WHERE id = :id
                """)
                .param("id", id)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.sql("""
                SELECT id, email, phone, password_hash, full_name, document_number, status
                  FROM users
                 WHERE email = :email
                """)
                .param("email", email)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public boolean activateWithPassword(UUID userId, String passwordHash) {
        int rows = jdbc.sql("""
                UPDATE users
                   SET password_hash = :hash,
                       status = 'ACTIVE',
                       updated_at = NOW()
                 WHERE id = :id
                   AND status = 'PENDING_ACTIVATION'
                """)
                .param("hash", passwordHash)
                .param("id", userId)
                .update();
        return rows == 1;
    }

    private User mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("document_number"),
                UserStatus.valueOf(rs.getString("status")));
    }
}
