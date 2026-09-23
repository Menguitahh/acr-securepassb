package com.securepass.modules.access.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.access.AccessDeniedException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class ExportAccessLogsUseCaseTest {

    private static final Instant TO = Instant.parse("2026-09-23T00:00:00Z");

    private AccessLogQuery query;
    private AccessLogExportAudit audit;
    private ExportAccessLogsUseCase useCase;
    private CurrentUser admin;

    @BeforeEach
    void setUp() {
        query = mock(AccessLogQuery.class);
        audit = mock(AccessLogExportAudit.class);
        useCase = new ExportAccessLogsUseCase(query, audit);
        admin = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), List.of(Role.ADMIN), List.of());
    }

    @Test
    void auditaAntesDeDevolverElFiltroDelBarrioDelActor() {
        UUID lot = UUID.randomUUID();
        Instant from = TO.minus(Duration.ofDays(7));

        AccessLogQuery.ExportFilter filter = useCase.start(admin, from, TO, lot, Boolean.FALSE);

        assertThat(filter).isEqualTo(new AccessLogQuery.ExportFilter(admin.neighborhoodId(), lot, from, TO, Boolean.FALSE));
        InOrder order = inOrder(audit, query);
        order.verify(audit).record(admin.userId(), filter);
        order.verifyNoMoreInteractions();
    }

    @Test
    void streamDelegaEnElQuery() {
        AccessLogQuery.ExportFilter filter = useCase.start(admin, TO.minus(Duration.ofDays(1)), TO, null, null);

        useCase.stream(filter, row -> {});

        verify(query).forEach(eq(filter), any());
    }

    @Test
    void rangoDeNoventaDiasExactosSePermite() {
        AccessLogQuery.ExportFilter filter = useCase.start(admin, TO.minus(Duration.ofDays(90)), TO, null, null);

        verify(audit).record(admin.userId(), filter);
    }

    @Test
    void rangoMayorANoventaDiasEs422SinAuditar() {
        Instant from = TO.minus(Duration.ofDays(90)).minusSeconds(1);

        assertThatThrownBy(() -> useCase.start(admin, from, TO, null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(audit, query);
    }

    @Test
    void toNoPosteriorAFromEs422() {
        assertThatThrownBy(() -> useCase.start(admin, TO, TO, null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void fromYToSonObligatorios() {
        assertThatThrownBy(() -> useCase.start(admin, null, TO, null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> useCase.start(admin, TO.minusSeconds(60), null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void noAdminEs403() {
        CurrentUser resident = new CurrentUser(
                UUID.randomUUID(), admin.neighborhoodId(), List.of(Role.RESIDENT_OWNER), List.of(UUID.randomUUID()));

        assertThatThrownBy(() -> useCase.start(resident, TO.minusSeconds(60), TO, null, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(audit);
    }

    @Test
    void adminSinBarrioEs403() {
        CurrentUser orphan = new CurrentUser(UUID.randomUUID(), null, List.of(Role.ADMIN), List.of());

        assertThatThrownBy(() -> useCase.start(orphan, TO.minusSeconds(60), TO, null, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(audit);
    }
}
