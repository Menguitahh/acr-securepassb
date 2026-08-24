package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.ActivationTokenRepository;
import com.securepass.modules.identity.domain.ActivationToken;
import com.securepass.modules.identity.domain.TokenPurpose;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ActivationTokenJdbcRepository implements ActivationTokenRepository {

    private final JdbcClient jdbc;

    public ActivationTokenJdbcRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(ActivationToken token) {
        jdbc.sql("""
                INSERT INTO activation_tokens
                    (id, user_id, purpose, token_hash, expires_at, consumed_at, created_at)
                VALUES
                    (:id, :userId, :purpose, :tokenHash, :expiresAt, :consumedAt, :createdAt)
                """)
                .param("id", token.id())
                .param("userId", token.userId())
                .param("purpose", token.purpose().name())
                .param("tokenHash", token.tokenHash())
                .param("expiresAt", Timestamp.from(token.expiresAt()))
                .param("consumedAt", token.consumedAt() == null ? null : Timestamp.from(token.consumedAt()))
                .param("createdAt", Timestamp.from(token.createdAt()))
                .update();
    }

    @Override
    public Optional<ActivationToken> findByHash(byte[] tokenHash) {
        return jdbc.sql("""
                SELECT id, user_id, purpose, token_hash, expires_at, consumed_at, created_at
                  FROM activation_tokens
                 WHERE token_hash = :hash
                """)
                .param("hash", tokenHash)
                .query(this::map)
                .optional();
    }

    @Override
    public boolean markConsumed(UUID tokenId) {
        int rows = jdbc.sql("""
                UPDATE activation_tokens
                   SET consumed_at = NOW()
                 WHERE id = :id
                   AND consumed_at IS NULL
                   AND expires_at > NOW()
                """)
                .param("id", tokenId)
                .update();
        return rows == 1;
    }

    @Override
    public int consumeAllActive(UUID userId, TokenPurpose purpose) {
        return jdbc.sql("""
                UPDATE activation_tokens
                   SET consumed_at = NOW()
                 WHERE user_id = :userId
                   AND purpose = :purpose
                   AND consumed_at IS NULL
                """)
                .param("userId", userId)
                .param("purpose", purpose.name())
                .update();
    }

    private ActivationToken map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp consumed = rs.getTimestamp("consumed_at");
        return new ActivationToken(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                TokenPurpose.valueOf(rs.getString("purpose")),
                rs.getBytes("token_hash"),
                rs.getTimestamp("expires_at").toInstant(),
                consumed == null ? null : consumed.toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
