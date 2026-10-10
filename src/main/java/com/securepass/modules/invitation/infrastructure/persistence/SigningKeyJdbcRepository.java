package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.PublicKeyView;
import com.securepass.modules.invitation.application.SigningKeyQuery;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.config.CacheConfig;
import lombok.AllArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class SigningKeyJdbcRepository implements SigningKeyRepository, SigningKeyQuery {

    private final JdbcClient jdbc;

    @Override
    public void save(SigningKey key) {
        jdbc.sql("""
                INSERT INTO signing_keys (id, neighborhood_id, kid, public_key, private_key_enc, active, rotated_at, created_at)
                VALUES (:id, :nid, :kid, :pub, :priv, :active, :rotated, :created)
                """)
                .param("id", key.id())
                .param("nid", key.neighborhoodId())
                .param("kid", key.kid())
                .param("pub", encodePublic(key.publicKey()))
                .param("priv", key.privateKeyEnc())
                .param("active", key.active())
                .param("rotated", key.rotatedAt() == null ? null : Timestamp.from(key.rotatedAt()))
                .param("created", Timestamp.from(key.createdAt()))
                .update();
    }

    @Override
    public Optional<SigningKey> findActive(UUID neighborhoodId) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, kid, public_key, private_key_enc, active, rotated_at, created_at
                  FROM signing_keys
                 WHERE neighborhood_id = :nid AND active = TRUE
                """)
                .param("nid", neighborhoodId)
                .query(this::mapRow)
                .optional();
    }

    // Cada escaneo de QR busca la clave por kid. Un kid inexistente no se
    // cachea (unless): una clave recien emitida se encuentra al primer pedido.
    @Override
    @Cacheable(cacheNames = CacheConfig.SIGNING_KEYS_BY_KID, key = "#neighborhoodId + ':' + #kid",
            unless = "#result == null")
    public Optional<SigningKey> findByKid(UUID neighborhoodId, String kid) {
        return jdbc.sql("""
                SELECT id, neighborhood_id, kid, public_key, private_key_enc, active, rotated_at, created_at
                  FROM signing_keys
                 WHERE neighborhood_id = :nid AND kid = :kid
                """)
                .param("nid", neighborhoodId)
                .param("kid", kid)
                .query(this::mapRow)
                .optional();
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.SIGNING_KEYS_BY_KID, allEntries = true)
    public void markInactive(UUID id, Instant rotatedAt) {
        jdbc.sql("""
                UPDATE signing_keys
                   SET active = FALSE, rotated_at = :rotated
                 WHERE id = :id
                """)
                .param("id", id)
                .param("rotated", Timestamp.from(rotatedAt))
                .update();
    }

    @Override
    public List<PublicKeyView> findActivePublicKeys(UUID neighborhoodId) {
        return jdbc.sql("""
                SELECT kid, public_key FROM signing_keys
                 WHERE neighborhood_id = :nid AND active = TRUE
                """)
                .param("nid", neighborhoodId)
                .query((rs, i) -> new PublicKeyView(rs.getString("kid"), decodePublic(rs.getString("public_key"))))
                .list();
    }

    private SigningKey mapRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp rotated = rs.getTimestamp("rotated_at");
        return new SigningKey(
                rs.getObject("id", UUID.class),
                rs.getObject("neighborhood_id", UUID.class),
                rs.getString("kid"),
                decodePublic(rs.getString("public_key")),
                rs.getString("private_key_enc"),
                rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant(),
                rotated == null ? null : rotated.toInstant());
    }

    private static String encodePublic(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] decodePublic(String encoded) {
        return Base64.getUrlDecoder().decode(encoded);
    }
}
