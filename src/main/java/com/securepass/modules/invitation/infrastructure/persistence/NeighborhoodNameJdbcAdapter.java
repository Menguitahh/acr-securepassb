package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.NeighborhoodNameQuery;
import com.securepass.shared.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class NeighborhoodNameJdbcAdapter implements NeighborhoodNameQuery {

    private final JdbcClient jdbc;

    public NeighborhoodNameJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Cacheable(CacheConfig.NEIGHBORHOOD_NAMES)
    public String findName(UUID neighborhoodId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return jdbc.sql("SELECT name FROM neighborhoods WHERE id = :id")
                .param("id", neighborhoodId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("barrio no existe: " + neighborhoodId));
    }
}
