package com.securepass.modules.access.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.RateLimitExceededException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExportAccessLogsUseCaseTest {

    private static final Instant TO = Instant.parse("2026-09-23T00:00:00Z");
    private static final ZoneId MENDOZA = ZoneId.of("America/Argentina/Mendoza");
    private static final int MAX_ROWS = 500_000;

    private AccessLogQuery query;
    private AccessLogExportAudit audit;
    private ExportAccessLogsUseCase useCase;
    private CurrentUser admin;

    @BeforeEach
    void setUp() {
        query = mock(AccessLogQuery.class);
        audit = mock(AccessLogExportAudit.class);
        useCase = new ExportAccessLogsUseCase(query, audit, 2, MAX_ROWS);
        admin = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), List.of(Role.ADMIN), List.of());
        when(query.timezone(admin.neighborhoodId())).thenReturn(MENDOZA);
    }

    // #192: el export sale en la hora del barrio del actor.
    @Test
    void elExportLlevaLaZonaDelBarrio() {
        try (ExportAccessLogsUseCase.Export export = start()) {
            assertThat(export.zone()).isEqualTo(MENDOZA);
        }
    }

    @Test
    void auditaAntesDeDevolverElFiltroDelBarrioDelActor() {
        UUID lot = UUID.randomUUID();
        Instant from = TO.minus(Duration.ofDays(7));

        AccessLogQuery.ExportFilter filter = useCase.start(admin, from, TO, lot, Boolean.FALSE, ExportFormat.CSV).filter();

        assertThat(filter).isEqualTo(new AccessLogQuery.ExportFilter(admin.neighborhoodId(), lot, from, TO, Boolean.FALSE));
        verify(audit).record(admin.userId(), filter, ExportFormat.CSV);
        verify(query, never()).forEach(any(), any());
    }

    @Test
    void streamDelegaEnElQuery() {
        ExportAccessLogsUseCase.Export export = useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null, ExportFormat.CSV);

        export.stream(row -> {});

        AccessLogQuery.ExportFilter filter = export.filter();

        verify(query).forEach(eq(filter), any());
    }

    @Test
    void rangoDeNoventaDiasExactosSePermite() {
        AccessLogQuery.ExportFilter filter = useCase.start(admin, TO.minus(Duration.ofDays(90)), TO, null, null, ExportFormat.CSV).filter();

        verify(audit).record(admin.userId(), filter, ExportFormat.CSV);
    }

    @Test
    void rangoMayorANoventaDiasEs422SinAuditar() {
        Instant from = TO.minus(Duration.ofDays(90)).minusSeconds(1);

        assertThatThrownBy(() -> useCase.start(admin, from, TO, null, null, ExportFormat.CSV))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(audit, query);
    }

    @Test
    void toNoPosteriorAFromEs422() {
        assertThatThrownBy(() -> useCase.start(admin, TO, TO, null, null, ExportFormat.CSV))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void fromYToSonObligatorios() {
        assertThatThrownBy(() -> useCase.start(admin, null, TO, null, null, ExportFormat.CSV))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> useCase.start(admin, TO.minusSeconds(60), null, null, null, ExportFormat.CSV))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void noAdminEs403() {
        CurrentUser resident = new CurrentUser(
                UUID.randomUUID(), admin.neighborhoodId(), List.of(Role.RESIDENT_OWNER), List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> useCase.start(resident, TO.minusSeconds(60), TO, null, null, ExportFormat.CSV))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void adminSinBarrioEs403() {
        CurrentUser orphan = new CurrentUser(UUID.randomUUID(), null, List.of(Role.ADMIN), List.of());

        assertThatThrownBy(() -> useCase.start(orphan, TO.minusSeconds(60), TO, null, null, ExportFormat.CSV))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(audit);
    }

    // #189: el cursor retiene una conexion del pool durante todo el export.
    @Test
    void tercerExportConcurrenteEs429SinAuditar() {
        ExportAccessLogsUseCase.Export a = start();
        ExportAccessLogsUseCase.Export b = start();
        clearInvocations(audit);

        assertThatThrownBy(this::start)
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).retryAfter()).isPositive());
        verifyNoInteractions(audit);

        a.close();
        b.close();
    }

    @Test
    void cerrarLiberaElSlotYEsIdempotente() {
        ExportAccessLogsUseCase.Export a = start();
        ExportAccessLogsUseCase.Export b = start();

        a.close();
        a.close();

        ExportAccessLogsUseCase.Export c = start();
        assertThatThrownBy(this::start).isInstanceOf(RateLimitExceededException.class);
        b.close();
        c.close();
    }

    @Test
    void siFallaLaAuditoriaSeLiberaElSlot() {
        doThrow(new IllegalStateException("db caida")).when(audit).record(any(), any(), any());
        assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(this::start).isInstanceOf(IllegalStateException.class);

        doNothing().when(audit).record(any(), any(), any());
        start().close();
        start().close();
    }

    @Test
    void siFallaElStreamSeLiberaAlCerrar() {
        doThrow(new IllegalStateException("cursor roto")).when(query).forEach(any(), any());
        for (int i = 0; i < 3; i++) {
            try (ExportAccessLogsUseCase.Export e = start()) {
                assertThatThrownBy(() -> e.stream(row -> {})).isInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void limiteDebeSerPositivo() {
        assertThatThrownBy(() -> new ExportAccessLogsUseCase(query, audit, 0, MAX_ROWS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // #195: el XLSX tiene tope de filas (medido) y el de Excel; el CSV no.
    @Test
    void xlsxSobreElLimiteDeFilasEs422SinSlotNiAuditoria() {
        when(query.count(any())).thenReturn((long) MAX_ROWS + 1);

        assertThatThrownBy(() -> useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null, ExportFormat.XLSX))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("CSV");
        verifyNoInteractions(audit);
        // No tomo slot: los dos siguen libres.
        start().close();
        start().close();
        try (var a = start(); var b = start()) {
            assertThatThrownBy(this::start).isInstanceOf(RateLimitExceededException.class);
        }
    }

    @Test
    void xlsxEnElLimiteSeAuditaComoXlsx() {
        when(query.count(any())).thenReturn((long) MAX_ROWS);

        try (var export = useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null, ExportFormat.XLSX)) {
            verify(audit).record(admin.userId(), export.filter(), ExportFormat.XLSX);
        }
    }

    @Test
    void csvNoCuentaFilas() {
        start().close();

        verify(query, never()).count(any());
    }

    // Un solo contador para los dos formatos: los dos retienen una conexion (#189).
    @Test
    void csvYXlsxCompartenLosSlots() {
        when(query.count(any())).thenReturn(1L);
        try (var csv = start();
             var xlsx = useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null, ExportFormat.XLSX)) {
            assertThatThrownBy(this::start).isInstanceOf(RateLimitExceededException.class);
        }
    }

    @Test
    void formatoEsObligatorio() {
        assertThatThrownBy(() -> useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void limiteDeFilasEntreUnoYElMaximoDeExcel() {
        assertThatThrownBy(() -> new ExportAccessLogsUseCase(query, audit, 2, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExportAccessLogsUseCase(query, audit, 2, 1_048_576))
                .isInstanceOf(IllegalArgumentException.class);
        new ExportAccessLogsUseCase(query, audit, 2, 1_048_575);
    }

    private ExportAccessLogsUseCase.Export start() {
        return useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null, ExportFormat.CSV);
    }
}
