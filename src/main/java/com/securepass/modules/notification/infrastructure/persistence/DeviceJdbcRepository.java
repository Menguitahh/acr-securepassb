package com.securepass.modules.notification.infrastructure.persistence;

import com.securepass.modules.notification.application.DeviceRepository;
import com.securepass.modules.notification.domain.Device;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@AllArgsConstructor
@Repository
public class DeviceJdbcRepository implements DeviceRepository {

    private final JdbcClient jdbc;

    @Override
    public Device upsertByToken(Device d) {
        // Un token es unico por dispositivo; el mismo aparato puede rotar de usuario
        // (cuenta compartida en familia). ON CONFLICT reasigna el user_id y refresca
        // last_seen_at para reflejar que el device sigue activo.
        jdbc.sql("""
                INSERT INTO devices (id, user_id, expo_push_token, platform, last_seen_at, created_at)
                VALUES (:id, :uid, :tok, :plat, :seen, :created)
                ON CONFLICT (expo_push_token) DO UPDATE
                    SET user_id      = EXCLUDED.user_id,
                        platform     = EXCLUDED.platform,
                        last_seen_at = EXCLUDED.last_seen_at
                """)
                .param("id", d.id())
                .param("uid", d.userId())
                .param("tok", d.expoPushToken())
                .param("plat", d.platform().name())
                .param("seen", Timestamp.from(d.lastSeenAt()))
                .param("created", Timestamp.from(d.createdAt()))
                .update();
        return findByToken(d.expoPushToken())
                .orElseThrow(() -> new IllegalStateException("device perdido tras upsert"));
    }

    @Override
    public List<Device> findByUserId(UUID userId) {
        return jdbc.sql("""
                SELECT id, user_id, expo_push_token, platform, last_seen_at, created_at
                  FROM devices
                 WHERE user_id = :uid
                 ORDER BY last_seen_at DESC
                """)
                .param("uid", userId)
                .query(mapper())
                .list();
    }

    @Override
    public boolean deleteByToken(String expoPushToken) {
        return jdbc.sql("DELETE FROM devices WHERE expo_push_token = :tok")
                .param("tok", expoPushToken)
                .update() > 0;
    }

    @Override
    public Optional<Device> findByToken(String expoPushToken) {
        return jdbc.sql("""
                SELECT id, user_id, expo_push_token, platform, last_seen_at, created_at
                  FROM devices
                 WHERE expo_push_token = :tok
                """)
                .param("tok", expoPushToken)
                .query(mapper())
                .optional();
    }

    private RowMapper<Device> mapper() {
        return (rs, i) -> new Device(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("expo_push_token"),
                Device.Platform.valueOf(rs.getString("platform")),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("created_at").toInstant());
    }
}
