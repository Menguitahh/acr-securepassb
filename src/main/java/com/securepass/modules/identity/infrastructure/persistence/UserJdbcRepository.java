package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserJdbcRepository implements UserRepository {

    private static final String SELECT_COLS =
            "id, email, phone, password_hash, full_name, document_number, birth_date, status";

    private final JdbcClient jdbc;

    public UserJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<User> findByIdentifier(String identifier) {
        return jdbc.sql("SELECT " + SELECT_COLS + " FROM users WHERE email = :id OR phone = :id LIMIT 1")
                .param("id", identifier)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT " + SELECT_COLS + " FROM users WHERE id = :id")
                .param("id", id)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return jdbc.sql("SELECT " + SELECT_COLS + " FROM users WHERE email = :email")
                .param("email", email)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public Optional<User> findByDocument(String documentNumber) {
        if (documentNumber == null || documentNumber.isBlank()) return Optional.empty();
        return jdbc.sql("SELECT " + SELECT_COLS + " FROM users WHERE document_number = :doc")
                .param("doc", documentNumber)
                .query(this::mapUser)
                .optional();
    }

    @Override
    public void create(User user) {
        jdbc.sql("""
                INSERT INTO users (id, email, phone, password_hash, full_name, document_number, birth_date, status)
                VALUES (:id, :email, :phone, :hash, :fullName, :doc, :birth, :status)
                """)
                .param("id", user.id())
                .param("email", user.email())
                .param("phone", user.phone())
                .param("hash", user.passwordHash())
                .param("fullName", user.fullName())
                .param("doc", user.documentNumber())
                .param("birth", user.birthDate() == null ? null : Date.valueOf(user.birthDate()))
                .param("status", user.status().name())
                .update();
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

    @Override
    public boolean replacePendingEmail(UUID userId, String email) {
        return jdbc.sql("""
                UPDATE users
                   SET email = :email,
                       updated_at = NOW()
                 WHERE id = :id
                   AND status = 'PENDING_ACTIVATION'
                """)
                .param("email", email)
                .param("id", userId)
                .update() == 1;
    }

    @Override
    public boolean onlyOwnedBy(UUID userId, UUID ownerId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid)
                   AND NOT EXISTS (
                        SELECT 1 FROM lot_permissions p
                         WHERE p.user_id = :uid
                           AND NOT EXISTS (SELECT 1 FROM lot_permissions o
                                            WHERE o.user_id = :owner
                                              AND o.lot_id = p.lot_id
                                              AND o.permission_type = 'OWNER'
                                              AND o.active = TRUE))
                """)
                .param("uid", userId)
                .param("owner", ownerId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public java.util.List<com.securepass.modules.identity.application.PendingActivation> pendingInNeighborhood(
            UUID neighborhoodId) {
        return jdbc.sql("""
                SELECT u.id, u.full_name, u.email, u.created_at,
                       COALESCE((SELECT array_agg(DISTINCT m.role::text) FROM memberships m
                                  WHERE m.user_id = u.id AND m.neighborhood_id = :nid AND m.active = TRUE),
                                '{}') AS roles,
                       COALESCE((SELECT array_agg(DISTINCT l.code) FROM lot_permissions p
                                   JOIN lots l ON l.id = p.lot_id
                                  WHERE p.user_id = u.id AND l.neighborhood_id = :nid AND p.active = TRUE),
                                '{}') AS lot_codes
                  FROM users u
                 WHERE u.status = 'PENDING_ACTIVATION'
                   AND (EXISTS (SELECT 1 FROM memberships m
                                 WHERE m.user_id = u.id AND m.neighborhood_id = :nid AND m.active = TRUE)
                        OR EXISTS (SELECT 1 FROM lot_permissions p JOIN lots l ON l.id = p.lot_id
                                    WHERE p.user_id = u.id AND l.neighborhood_id = :nid AND p.active = TRUE))
                 ORDER BY u.created_at DESC
                 LIMIT 200
                """)
                .param("nid", neighborhoodId)
                .query((rs, i) -> new com.securepass.modules.identity.application.PendingActivation(
                        rs.getObject("id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        java.util.List.of((String[]) rs.getArray("roles").getArray()),
                        java.util.List.of((String[]) rs.getArray("lot_codes").getArray()),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    @Override
    public boolean belongsToNeighborhood(UUID userId, UUID neighborhoodId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM memberships m
                                WHERE m.user_id = :uid AND m.neighborhood_id = :nid AND m.active = TRUE)
                    OR EXISTS (SELECT 1 FROM lot_permissions p JOIN lots l ON l.id = p.lot_id
                                WHERE p.user_id = :uid AND l.neighborhood_id = :nid AND p.active = TRUE)
                """)
                .param("uid", userId)
                .param("nid", neighborhoodId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean belongsToLotAsMemberOrWorker(UUID userId, UUID lotId) {
        return jdbc.sql("""
                SELECT 1 FROM lot_permissions
                 WHERE lot_id = :lot AND user_id = :uid AND active = TRUE
                   AND permission_type IN ('MEMBER', 'WORKER')
                 LIMIT 1
                """)
                .param("lot", lotId)
                .param("uid", userId)
                .query(Integer.class)
                .optional()
                .isPresent();
    }

    @Override
    public boolean completeEmail(UUID userId, String email) {
        int rows = jdbc.sql("""
                UPDATE users
                   SET email = :email,
                       updated_at = NOW()
                 WHERE id = :id
                   AND email IS NULL
                """)
                .param("email", email)
                .param("id", userId)
                .update();
        return rows == 1;
    }

    @Override
    public boolean updatePassword(UUID userId, String passwordHash) {
        int rows = jdbc.sql("""
                UPDATE users
                   SET password_hash = :hash,
                       updated_at = NOW()
                 WHERE id = :id
                   AND status = 'ACTIVE'
                """)
                .param("hash", passwordHash)
                .param("id", userId)
                .update();
        return rows == 1;
    }

    private User mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Date bd = rs.getDate("birth_date");
        LocalDate birthDate = bd == null ? null : bd.toLocalDate();
        return new User(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("password_hash"),
                rs.getString("full_name"),
                rs.getString("document_number"),
                birthDate,
                UserStatus.valueOf(rs.getString("status")));
    }
}
