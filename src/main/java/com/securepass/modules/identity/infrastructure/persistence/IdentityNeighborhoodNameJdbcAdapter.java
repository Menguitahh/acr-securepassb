package com.securepass.modules.identity.infrastructure.persistence;

import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class IdentityNeighborhoodNameJdbcAdapter implements NeighborhoodNameQuery {

    private final JdbcClient jdbc;

    public IdentityNeighborhoodNameJdbcAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String findName(UUID neighborhoodId) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        return jdbc.sql("SELECT name FROM neighborhoods WHERE id = :id")
                .param("id", neighborhoodId)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("barrio no existe: " + neighborhoodId));
    }
}
