package com.securepass.modules.invitation.infrastructure.persistence;

import com.securepass.modules.invitation.application.NeighborhoodExtensionPolicy;
import com.securepass.modules.invitation.application.NeighborhoodExtensionPolicyQuery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class NeighborhoodExtensionPolicyJdbcQuery implements NeighborhoodExtensionPolicyQuery {

    private final JdbcClient jdbc;

    public NeighborhoodExtensionPolicyJdbcQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NeighborhoodExtensionPolicy> find(UUID neighborhoodId) {
        return jdbc.sql("SELECT max_extensions, extension_minutes FROM neighborhoods WHERE id = :id")
                .param("id", neighborhoodId)
                .query((rs, i) -> new NeighborhoodExtensionPolicy(
                        rs.getInt("max_extensions"),
                        rs.getInt("extension_minutes")))
                .optional();
    }
}
