package com.securepass.tools;

import com.securepass.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** La demo arranca entera sobre una base limpia y no se duplica al reiniciar. */
@Testcontainers
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
@SpringBootTest(properties = {"securepass.demo.seed=true", "securepass.dev.seed=false"})
class DemoSeedRunnerTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DemoSeedRunner runner;
    @Autowired PasswordEncoder encoder;

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void siembraLosDosBarriosConCuentasEHistoria() {
        assertThat(count("SELECT count(*) FROM neighborhoods WHERE slug IN ('los-pinos','altos-del-sol')")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM lots l JOIN neighborhoods n ON n.id = l.neighborhood_id WHERE n.slug = 'los-pinos'"))
                .isEqualTo(12);

        for (String email : new String[]{"admin@lospinos.example", "guardia@lospinos.example",
                "titular@lospinos.example", "familiar@lospinos.example", "trabajador@lospinos.example"}) {
            String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ? AND status = 'ACTIVE'",
                    String.class, email);
            assertThat(encoder.matches(DemoSeedRunner.PASSWORD, hash)).as(email).isTrue();
        }
        assertThat(count("""
                SELECT count(*) FROM platform_admins p JOIN users u ON u.id = p.user_id
                 WHERE u.email = 'plataforma@securepass.example'
                """)).isEqualTo(1);
        // Altos del Sol: admin sin activar, sin cuentas para usar.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = 'admin@altosdelsol.example'", String.class))
                .isEqualTo("PENDING_ACTIVATION");

        String pinos = "(SELECT id FROM neighborhoods WHERE slug = 'los-pinos')";
        assertThat(count("SELECT count(*) FROM access_logs WHERE neighborhood_id = " + pinos)).isGreaterThan(50);
        assertThat(count("SELECT count(*) FROM access_logs WHERE result = 'DENIED' AND neighborhood_id = " + pinos))
                .isGreaterThan(3);
        assertThat(count("SELECT count(*) FROM access_logs WHERE captured_at > now() AND neighborhood_id = " + pinos))
                .isZero();
        assertThat(count("SELECT count(*) FROM stays WHERE exited_at IS NULL AND neighborhood_id = " + pinos))
                .as("adentro ahora: Marcos Vera y Carla Nunez").isEqualTo(2);
        assertThat(count("""
                SELECT count(*) FROM stays s JOIN neighborhoods n ON n.id = s.neighborhood_id
                 WHERE n.slug = 'los-pinos' AND s.exited_at IS NULL
                   AND s.entered_at + n.stay_alert_minutes * interval '1 minute' < now()
                """)).as("una permanencia excedida para la vista Permanencia").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM guard_shifts WHERE ended_at IS NULL AND neighborhood_id = " + pinos))
                .as("el guardia abre su turno en vivo").isZero();
        assertThat(count("SELECT count(*) FROM invitations WHERE status = 'ACTIVE' AND resident_pass = FALSE"
                + " AND neighborhood_id = " + pinos)).isEqualTo(3);

        int logs = count("SELECT count(*) FROM access_logs");
        runner.run();
        assertThat(count("SELECT count(*) FROM access_logs")).isEqualTo(logs);
    }
}
