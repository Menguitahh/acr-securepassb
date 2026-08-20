package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.LotStatusPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class LotStatusJdbcPort implements LotStatusPort {

    private final JdbcClient jdbc;

    public LotStatusJdbcPort(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> find(UUID lotId) {
        return jdbc.sql("SELECT status FROM lots WHERE id = :id")
                .param("id", lotId)
                .query(String.class)
                .optional();
    }
}
