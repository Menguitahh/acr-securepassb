package com.securepass.modules.neighborhood.infrastructure.persistence;

import com.securepass.modules.neighborhood.application.NeighborhoodCatalog;
import com.securepass.modules.neighborhood.application.NeighborhoodOverview;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * securepassb#258: barrios y lotes salen por JPA; los admins, de memberships
 * con SQL (memberships no esta mapeada).
 *
 * ponytail: una consulta de conteo por barrio; agregar un GROUP BY si la
 * plataforma pasa de unas decenas de barrios.
 */
@AllArgsConstructor
@Repository
public class NeighborhoodCatalogJpaAdapter implements NeighborhoodCatalog {

    private final NeighborhoodJpaRepository neighborhoods;
    private final LotJpaRepository lots;
    private final JdbcClient jdbc;

    @Override
    public List<NeighborhoodOverview> overview() {
        Map<UUID, List<NeighborhoodOverview.Admin>> admins = jdbc.sql("""
                SELECT m.neighborhood_id, u.full_name, u.email, u.status
                  FROM memberships m
                  JOIN users u ON u.id = m.user_id
                 WHERE m.role = 'ROLE_ADMIN' AND m.active = TRUE
                 ORDER BY u.full_name
                """)
                .query((rs, i) -> Map.entry(rs.getObject("neighborhood_id", UUID.class),
                        new NeighborhoodOverview.Admin(rs.getString("full_name"), rs.getString("email"),
                                rs.getString("status"))))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return neighborhoods.findAllByOrderByNameAsc().stream()
                .map(n -> new NeighborhoodOverview(n.getId(), n.getName(), n.getSlug(), n.getTimezone(),
                        n.getCreatedAt(), lots.countByNeighborhoodId(n.getId()),
                        admins.getOrDefault(n.getId(), List.of())))
                .toList();
    }
}
