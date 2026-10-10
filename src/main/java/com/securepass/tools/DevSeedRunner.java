package com.securepass.tools;

import com.securepass.modules.neighborhood.application.NeighborhoodAlreadyExistsException;
import com.securepass.modules.neighborhood.application.NeighborhoodProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.UUID;

/**
 * Seeder de desarrollo. Reemplaza el docs/seed.sql viejo: en vez de duplicar
 * los INSERT de neighborhood/gate/admin, llama al mismo bean que aprovisiona
 * un barrio en prod ({@link NeighborhoodProvisioner}) con IDs fijos, y luego
 * corre un overlay SQL con lo que es dev-only (guard, owner, lot, permisos y
 * la activacion forzada del admin).
 *
 * Doble barrera para no auto-seedear un ambiente por accidente:
 *   1) perfil {@code dev} activo
 *   2) property {@code securepass.dev.seed=true} (default false)
 *
 * Idempotente: si el barrio ya existe (por slug) no hace nada.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "securepass.dev.seed", havingValue = "true")
public class DevSeedRunner implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevSeedRunner.class);

    private static final String DEV_SLUG = "barrio-dev";
    private static final UUID DEV_NEIGHBORHOOD_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEV_GATE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEV_ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final NeighborhoodProvisioner provisioner;
    private final JdbcClient jdbc;
    private final DataSource dataSource;
    private final String activeProfiles;

    public DevSeedRunner(
            NeighborhoodProvisioner provisioner,
            JdbcClient jdbc,
            DataSource dataSource,
            @Value("${spring.profiles.active:}") String activeProfiles) {
        this.provisioner = provisioner;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.activeProfiles = activeProfiles;
    }

    @Override
    public void run(String... args) throws Exception {
        LOG.warn("========================================================================");
        LOG.warn("DevSeedRunner ACTIVO. Perfiles={} securepass.dev.seed=true.", activeProfiles);
        LOG.warn("Inserta usuarios con passwords conocidas (admin/guard/owner @dev.local).");
        LOG.warn("NUNCA debe correr fuera de una maquina de desarrollo.");
        LOG.warn("========================================================================");

        // Idempotente: va antes del corte de "barrio ya sembrado".
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("dev/dev-platform.sql"));
        }

        boolean exists = jdbc.sql("SELECT 1 FROM neighborhoods WHERE slug = :s")
                .param("s", DEV_SLUG).query(Integer.class).optional().isPresent();
        if (exists) {
            LOG.info("Barrio dev '{}' ya existe. Nada que sembrar.", DEV_SLUG);
            return;
        }

        NeighborhoodProvisioner.Command cmd = new NeighborhoodProvisioner.Command(
                "Barrio Dev", DEV_SLUG, "America/Argentina/Mendoza", "Garita Principal",
                "admin@dev.local", "Admin Dev", "30000001");
        NeighborhoodProvisioner.Ids ids = new NeighborhoodProvisioner.Ids(
                DEV_NEIGHBORHOOD_ID, DEV_GATE_ID, DEV_ADMIN_ID);

        try {
            provisioner.provision(cmd, ids);
        } catch (NeighborhoodAlreadyExistsException e) {
            // Race con otro proceso; tratamos como idempotente.
            LOG.info("Barrio dev ya provisionado por otro proceso ({}={}).", e.by(), e.value());
            return;
        }

        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new ClassPathResource("dev/dev-overlay.sql"));
        }

        LOG.info("Seed dev completo. neighborhoodId={} adminUserId={} lotId=33333333-3333-3333-3333-333333333333",
                DEV_NEIGHBORHOOD_ID, DEV_ADMIN_ID);
    }
}
