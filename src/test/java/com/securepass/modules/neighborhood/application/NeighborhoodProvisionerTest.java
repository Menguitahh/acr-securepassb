package com.securepass.modules.neighborhood.application;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NeighborhoodProvisionerTest {

    @Autowired NeighborhoodProvisioner provisioner;
    @Autowired JdbcTemplate jdbc;

    // signingKeyIssuer se mockea SOLO en el test de rollback. En el resto,
    // usa el bean real contra la DB de Testcontainers.
    @MockBean SigningKeyIssuer signingKeyIssuer;

    @org.junit.jupiter.api.BeforeEach
    void resetMock() {
        Mockito.reset(signingKeyIssuer);
        // Default: comportamiento real via delegate no aplica aca; devolvemos
        // una IssuedKey coherente que otros tests puedan chequear.
        Mockito.when(signingKeyIssuer.issueForNeighborhood(any(UUID.class)))
                .thenAnswer(inv -> {
                    UUID nid = inv.getArgument(0);
                    // Insertamos una signing key real en DB para que quede el estado consistente.
                    jdbc.update("""
                            INSERT INTO signing_keys (id, neighborhood_id, kid, public_key, private_key_enc, active, created_at)
                            VALUES (?, ?, ?, decode('deadbeef','hex'), 'enc', TRUE, now())
                            """, UUID.randomUUID(), nid, "kid-" + nid.toString().substring(0, 8));
                    return new SigningKeyIssuer.IssuedKey("kid-" + nid.toString().substring(0, 8), new byte[]{1, 2, 3, 4});
                });
    }

    private NeighborhoodProvisioner.Command validCommand(String slug) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return new NeighborhoodProvisioner.Command(
                "Barrio " + suffix, slug, "America/Argentina/Mendoza", null,
                "admin-" + suffix + "@t.com", "Admin " + suffix,
                "30" + (100000 + Math.abs(suffix.hashCode() % 900000)));
    }

    @Test
    void happyPath_crea_los_4_recursos_en_una_tx() {
        String slug = "hp-" + UUID.randomUUID().toString().substring(0, 6);
        NeighborhoodProvisioner.Command cmd = validCommand(slug);

        NeighborhoodProvisioner.ProvisionOutcome outcome = provisioner.provision(cmd);

        assertThat(outcome.result().neighborhoodId()).isNotNull();
        assertThat(outcome.result().gateId()).isNotNull();
        assertThat(outcome.result().adminUserId()).isNotNull();
        assertThat(outcome.result().signingKeyKid()).isNotBlank();
        assertThat(outcome.activationTokenPlaintext()).isNotBlank();

        UUID nid = outcome.result().neighborhoodId();
        assertThat(jdbc.queryForObject(
                "SELECT slug FROM neighborhoods WHERE id=?", String.class, nid)).isEqualTo(slug);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM signing_keys WHERE neighborhood_id=? AND active=TRUE",
                Integer.class, nid)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM gates WHERE neighborhood_id=? AND active=TRUE",
                Integer.class, nid)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM users WHERE id=?", String.class,
                outcome.result().adminUserId())).isEqualTo("PENDING_ACTIVATION");
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id=?", String.class,
                outcome.result().adminUserId())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM memberships WHERE user_id=? AND neighborhood_id=? AND role='ROLE_ADMIN' AND active=TRUE",
                Integer.class, outcome.result().adminUserId(), nid)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM activation_tokens WHERE user_id=? AND consumed_at IS NULL",
                Integer.class, outcome.result().adminUserId())).isEqualTo(1);
    }

    @Test
    void slug_duplicado_falla_y_no_escribe_nada() {
        String slug = "dup-" + UUID.randomUUID().toString().substring(0, 6);
        provisioner.provision(validCommand(slug));

        long neighborhoodsAntes = jdbc.queryForObject("SELECT count(*) FROM neighborhoods", Long.class);
        long gatesAntes = jdbc.queryForObject("SELECT count(*) FROM gates", Long.class);
        long usersAntes = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);

        NeighborhoodProvisioner.Command cmd2 = validCommand(slug);
        assertThatThrownBy(() -> provisioner.provision(cmd2))
                .isInstanceOf(NeighborhoodAlreadyExistsException.class)
                .satisfies(ex -> {
                    NeighborhoodAlreadyExistsException e = (NeighborhoodAlreadyExistsException) ex;
                    assertThat(e.by()).isEqualTo("slug");
                    assertThat(e.value()).isEqualTo(slug);
                });

        // Nada extra escrito.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM neighborhoods", Long.class)).isEqualTo(neighborhoodsAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gates", Long.class)).isEqualTo(gatesAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(usersAntes);
    }

    @Test
    void name_duplicado_falla_case_insensitive() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Barrio " + suffix;
        // "30111" + %03d de un numero 0-999 => siempre 8 digitos y matchea DNI_RX
        // (7 u 8 digitos). Antes usaba concatenacion cruda que rendia 5-8 y
        // fallaba flakemente cuando el hash caia bajo (CI 2026-08-29).
        String dniSuffix = String.format("%03d", Math.abs(suffix.hashCode() % 1000));
        provisioner.provision(new NeighborhoodProvisioner.Command(
                name, "n1-" + suffix, "America/Argentina/Mendoza", null,
                "a@t.com".replace("@", "-" + suffix + "@"), "A", "30111" + dniSuffix));

        // Mismo nombre en mayusculas -> tambien colisiona.
        NeighborhoodProvisioner.Command cmd2 = new NeighborhoodProvisioner.Command(
                name.toUpperCase(), "n2-" + suffix, "America/Argentina/Mendoza", null,
                "b@t.com".replace("@", "-" + suffix + "@"), "B", "30222" + dniSuffix);
        assertThatThrownBy(() -> provisioner.provision(cmd2))
                .isInstanceOf(NeighborhoodAlreadyExistsException.class)
                .satisfies(ex -> assertThat(((NeighborhoodAlreadyExistsException) ex).by()).isEqualTo("name"));
    }

    @Test
    void falla_en_paso_intermedio_rollback_total() {
        Mockito.reset(signingKeyIssuer);
        Mockito.when(signingKeyIssuer.issueForNeighborhood(any(UUID.class)))
                .thenThrow(new RuntimeException("crypto down"));

        String slug = "rb-" + UUID.randomUUID().toString().substring(0, 6);
        long neighborhoodsAntes = jdbc.queryForObject("SELECT count(*) FROM neighborhoods", Long.class);
        long usersAntes = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        long gatesAntes = jdbc.queryForObject("SELECT count(*) FROM gates", Long.class);
        long tokensAntes = jdbc.queryForObject("SELECT count(*) FROM activation_tokens", Long.class);

        NeighborhoodProvisioner.Command cmd = validCommand(slug);
        assertThatThrownBy(() -> provisioner.provision(cmd))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("crypto down");

        // Rollback total: cero filas extras.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM neighborhoods", Long.class)).isEqualTo(neighborhoodsAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(usersAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gates", Long.class)).isEqualTo(gatesAntes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM activation_tokens", Long.class)).isEqualTo(tokensAntes);
    }

    @Test
    void command_valida_slug_regex() {
        assertThatThrownBy(() -> new NeighborhoodProvisioner.Command(
                "X", "MAYUS", "America/Argentina/Mendoza", null, "a@t.com", "A", "30111222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slug invalido");
    }

    @Test
    void command_valida_dni_regex() {
        assertThatThrownBy(() -> new NeighborhoodProvisioner.Command(
                "X", "x", "America/Argentina/Mendoza", null, "a@t.com", "A", "123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adminDni");
    }

    @Test
    void command_valida_email() {
        assertThatThrownBy(() -> new NeighborhoodProvisioner.Command(
                "X", "x", "America/Argentina/Mendoza", null, "no-email", "A", "30111222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adminEmail");
    }

    @Test
    void command_default_gate() {
        NeighborhoodProvisioner.Command cmd = new NeighborhoodProvisioner.Command(
                "X", "x", "America/Argentina/Mendoza", null, "a@t.com", "A", "30111222");
        assertThat(cmd.gateName()).isEqualTo("Principal");
    }

    @Test
    void command_exige_timezone_explicita() {
        // Sin default: no queremos que un barrio de otra provincia herede
        // silenciosamente Mendoza y evalue RECURRING con la tz equivocada.
        assertThatThrownBy(() -> new NeighborhoodProvisioner.Command(
                "X", "x", null, null, "a@t.com", "A", "30111222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timezone");

        assertThatThrownBy(() -> new NeighborhoodProvisioner.Command(
                "X", "x", "  ", null, "a@t.com", "A", "30111222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timezone");
    }

    @Test
    void command_rechaza_timezone_invalida() {
        assertThatThrownBy(() -> new NeighborhoodProvisioner.Command(
                "X", "x", "Not/A_Zone", null, "a@t.com", "A", "30111222"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timezone invalido");
    }
}
