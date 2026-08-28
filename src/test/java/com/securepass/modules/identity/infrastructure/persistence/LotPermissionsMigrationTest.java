package com.securepass.modules.identity.infrastructure.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS-106: la migracion V10 copia family_members a lot_permissions sin
 * perdida. Corre V1..V9 primero, siembra un fixture rico (owner, member,
 * inactivo, relation null, "se fue y volvio" via UPDATE), corre V10 y
 * verifica el uno-a-uno mas los invariantes de UNIQUE.
 */
@Testcontainers
class LotPermissionsMigrationTest {

    @Container
    static final PostgreSQLContainer<?> pg =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void migrationPreservesFamilyMembers() throws Exception {
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();

        UUID neighborhoodId = UUID.randomUUID();
        UUID lotA = UUID.randomUUID();
        UUID lotB = UUID.randomUUID();
        UUID uOwner = UUID.randomUUID();
        UUID uMember = UUID.randomUUID();
        UUID uInactive = UUID.randomUUID();
        UUID uNoRelation = UUID.randomUUID();
        UUID uReactivated = UUID.randomUUID();

        try (Connection c = connect()) {
            c.setAutoCommit(true);
            exec(c, "INSERT INTO neighborhoods (id, name, slug) VALUES (?, ?, ?)",
                    neighborhoodId, "Test Country", "test-country");
            exec(c, "INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                    lotA, neighborhoodId, "A-01");
            exec(c, "INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, ?)",
                    lotB, neighborhoodId, "B-02");

            for (UUID u : new UUID[]{uOwner, uMember, uInactive, uNoRelation, uReactivated}) {
                exec(c, """
                        INSERT INTO users (id, email, password_hash, full_name, status)
                        VALUES (?, ?, ?, ?, 'ACTIVE')
                        """, u, u + "@t.local", "$argon2id$dummy", "User " + u.toString().substring(0, 4));
            }

            // Fixture:
            //  - owner activo en lot A, relation "Titular"
            //  - member activo en lot A, can_invite=false, relation "Hijo/a"
            //  - member inactivo en lot B, relation "Ex-inquilino"
            //  - owner activo en lot B con relation NULL
            //  - "se fue y volvio": member que se dio de baja y volvio a activar
            //    (una sola fila que fue UPDATE-ada, no dos filas). Se representa
            //    como una fila active=true; para validar que el UNIQUE de
            //    lot_permissions permite el ciclo, hacemos UPDATE post-migracion.
            exec(c, """
                    INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                    VALUES (?, ?, 'Titular', TRUE, TRUE, TRUE)
                    """, lotA, uOwner);
            exec(c, """
                    INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                    VALUES (?, ?, 'Hijo/a', FALSE, FALSE, TRUE)
                    """, lotA, uMember);
            exec(c, """
                    INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                    VALUES (?, ?, 'Ex-inquilino', FALSE, TRUE, FALSE)
                    """, lotB, uInactive);
            exec(c, """
                    INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                    VALUES (?, ?, NULL, TRUE, TRUE, TRUE)
                    """, lotB, uNoRelation);
            exec(c, """
                    INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
                    VALUES (?, ?, 'Volvio', FALSE, TRUE, TRUE)
                    """, lotA, uReactivated);
        }

        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .target("10")
                .load()
                .migrate();

        try (Connection c = connect()) {
            assertThat(count(c, "SELECT count(*) FROM family_members")).isEqualTo(5);
            assertThat(count(c, "SELECT count(*) FROM lot_permissions")).isEqualTo(5);

            // Cada family_members tiene su equivalente exacto en lot_permissions.
            int mismatch = count(c, """
                    SELECT count(*)
                    FROM family_members f
                    LEFT JOIN lot_permissions p
                      ON p.user_id = f.user_id
                     AND p.lot_id  = f.lot_id
                     AND p.permission_type = CASE WHEN f.is_owner THEN 'OWNER' ELSE 'MEMBER' END
                     AND p.active  = f.active
                     AND (p.attributes->>'can_invite')::boolean = f.can_invite
                     AND (p.attributes->>'relation') IS NOT DISTINCT FROM f.relation
                    WHERE p.id IS NULL
                    """);
            assertThat(mismatch).isEqualTo(0);

            assertThat(queryString(c,
                    "SELECT permission_type FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                    uOwner, lotA)).isEqualTo("OWNER");
            assertThat(queryString(c,
                    "SELECT permission_type FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                    uMember, lotA)).isEqualTo("MEMBER");
            assertThat(queryBool(c,
                    "SELECT active FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                    uInactive, lotB)).isFalse();
            assertThat(queryString(c,
                    "SELECT attributes->>'relation' FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                    uNoRelation, lotB)).isNull();

            // "Se fue y volvio" via UPDATE: la fila migrada active=true pasa a
            // false y vuelve a true. Debe seguir siendo una sola fila; el
            // UNIQUE (user_id, lot_id, permission_type) no se viola.
            exec(c, "UPDATE lot_permissions SET active = FALSE WHERE user_id = ? AND lot_id = ?",
                    uReactivated, lotA);
            exec(c, "UPDATE lot_permissions SET active = TRUE  WHERE user_id = ? AND lot_id = ?",
                    uReactivated, lotA);
            assertThat(count(c,
                    "SELECT count(*) FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                    uReactivated, lotA)).isEqualTo(1);
            assertThat(queryBool(c,
                    "SELECT active FROM lot_permissions WHERE user_id = ? AND lot_id = ?",
                    uReactivated, lotA)).isTrue();
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private static int count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String queryString(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String v = rs.getString(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private static boolean queryBool(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
