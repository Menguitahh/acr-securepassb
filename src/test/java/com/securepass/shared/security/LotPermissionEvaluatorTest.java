package com.securepass.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LotPermissionEvaluatorTest {

    JdbcClient jdbc;
    JdbcClient.StatementSpec statement;
    JdbcClient.MappedQuerySpec<String> mapped;
    LotPermissionEvaluator evaluator;

    UUID user = UUID.randomUUID();
    UUID lot = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jdbc = mock(JdbcClient.class);
        statement = mock(JdbcClient.StatementSpec.class);
        mapped = mock(JdbcClient.MappedQuerySpec.class);

        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.param(anyString(), any())).thenReturn(statement);
        when(statement.query(eq(String.class))).thenReturn(mapped);

        evaluator = new LotPermissionEvaluator(jdbc, new com.securepass.shared.security.AccessGuard());
    }

    // La query filtra por active=TRUE en SQL. Estos tests operan sobre lo
    // que la query DEVUELVE: fila activa presente/ausente y variaciones.

    @Test
    void permiso_existente_y_activo_devuelve_true() {
        when(mapped.list()).thenReturn(List.of("OWNER"));
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.OWNER)).isTrue();
    }

    @Test
    void permiso_inactivo_no_aparece_en_query_devuelve_false() {
        // active=false -> la query lo filtra en SQL -> lista vacia.
        when(mapped.list()).thenReturn(List.of());
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.MEMBER)).isFalse();
    }

    @Test
    void permiso_no_existe_devuelve_false() {
        when(mapped.list()).thenReturn(List.of());
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.WORKER)).isFalse();
    }

    @Test
    void lote_inexistente_devuelve_false() {
        // Lote inexistente = 0 filas en lot_permissions.
        when(mapped.list()).thenReturn(List.of());
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.OWNER)).isFalse();
    }

    @Test
    void owner_en_lote_A_preguntando_por_lote_B_devuelve_false() {
        // Mismo user, distinto lot: la query con :lid=B no devuelve la fila de A.
        when(mapped.list()).thenReturn(List.of());
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.OWNER)).isFalse();
    }

    @Test
    void owner_mas_member_sobre_mismo_lote_gana_owner() {
        when(mapped.list()).thenReturn(List.of("MEMBER", "OWNER"));

        // Consultando OWNER pasa: highest=OWNER >= OWNER.
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.OWNER)).isTrue();
        // Consultando MEMBER tambien pasa: OWNER >= MEMBER.
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.MEMBER)).isTrue();
    }

    @Test
    void member_solo_no_alcanza_para_owner() {
        when(mapped.list()).thenReturn(List.of("MEMBER"));
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.OWNER)).isFalse();
    }

    @Test
    void query_falla_devuelve_false_no_propaga() {
        when(mapped.list()).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.WORKER)).isFalse();
    }

    @Test
    void argumentos_null_devuelven_false() {
        assertThat(evaluator.hasAtLeast(null, lot, LotPermissionType.OWNER)).isFalse();
        assertThat(evaluator.hasAtLeast(user, null, LotPermissionType.OWNER)).isFalse();
        assertThat(evaluator.hasAtLeast(user, lot, null)).isFalse();
    }

    @Test
    void permission_type_desconocido_se_ignora_no_autoriza() {
        // Solo hay una fila y con valor invalido: fail-closed.
        when(mapped.list()).thenReturn(List.of("SUPERADMIN"));
        assertThat(evaluator.hasAtLeast(user, lot, LotPermissionType.WORKER)).isFalse();
    }
}
