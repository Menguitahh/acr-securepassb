package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.neighborhood.application.MembershipGranter;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

@AllArgsConstructor
@Component
public class MembershipJdbcGranter implements MembershipGranter {

    private final JdbcClient jdbc;

    @Override
    public void grant(UUID userId, UUID neighborhoodId, Role role) {
        jdbc.sql("""
                INSERT INTO memberships (user_id, neighborhood_id, role, active)
                VALUES (:u, :n, :r, TRUE)
                ON CONFLICT (user_id, neighborhood_id, role) DO UPDATE SET active = TRUE
                """)
                .param("u", userId).param("n", neighborhoodId).param("r", role.authority())
                .update();
    }

    @Override
    public boolean exists(UUID userId, UUID neighborhoodId, Role role) {
        return jdbc.sql("""
                SELECT 1 FROM memberships
                 WHERE user_id = :u AND neighborhood_id = :n AND role = :r
                """)
                .param("u", userId).param("n", neighborhoodId).param("r", role.authority())
                .query(Integer.class).optional().isPresent();
    }
}
