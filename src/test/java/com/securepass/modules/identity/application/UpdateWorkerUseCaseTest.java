package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.CurrentUser;
import com.securepass.shared.security.WorkerPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UpdateWorkerUseCaseTest {

    private WorkerPermissionRepository workers;
    private UserRepository users;
    private UpdateWorkerUseCase useCase;
    private CurrentUser actor;
    private UUID lotId;
    private UUID workerId;
    private User worker;

    @BeforeEach
    void setUp() {
        workers = mock(WorkerPermissionRepository.class);
        users = mock(UserRepository.class);
        useCase = new UpdateWorkerUseCase(workers, users);

        actor = new CurrentUser(UUID.randomUUID(), UUID.randomUUID(),
                List.of(Role.RESIDENT_OWNER), List.of());
        lotId = UUID.randomUUID();
        workerId = UUID.randomUUID();
        worker = new User(workerId, "w@dev.local", null, "h", "Juan Jardinero",
                "30111222", null, UserStatus.ACTIVE);
        when(users.findById(workerId)).thenReturn(Optional.of(worker));
    }

    private WorkerPermission current(boolean active) {
        return new WorkerPermission(workerId, lotId, "Jardineria",
                new ScheduleWindow(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0)),
                active);
    }

    @Test
    void bajaPrimeraVezDesactiva() {
        when(workers.find(workerId, lotId))
                .thenReturn(Optional.of(current(true)))
                .thenReturn(Optional.of(current(false)));

        UpdateWorkerUseCase.Result r = useCase.update(actor, lotId, workerId, null, null, false);

        assertThat(r.outcome()).isEqualTo(UpdateWorkerUseCase.Outcome.DEACTIVATED);
        assertThat(r.active()).isFalse();
        verify(workers).deactivate(workerId, lotId);
    }

    @Test
    void bajaIdempotenteNoRedeactiva() {
        when(workers.find(workerId, lotId)).thenReturn(Optional.of(current(false)));

        UpdateWorkerUseCase.Result r = useCase.update(actor, lotId, workerId, null, null, false);

        assertThat(r.outcome()).isEqualTo(UpdateWorkerUseCase.Outcome.ALREADY_INACTIVE);
        verify(workers, never()).deactivate(any(), any());
    }

    @Test
    void editServiceYScheduleActualiza() {
        ScheduleWindow newSchedule = new ScheduleWindow(
                List.of(1, 3, 5), LocalTime.of(9, 0), LocalTime.of(13, 0));
        when(workers.find(workerId, lotId))
                .thenReturn(Optional.of(current(true)))
                .thenReturn(Optional.of(new WorkerPermission(
                        workerId, lotId, "Limpieza", newSchedule, true)));

        UpdateWorkerUseCase.Result r = useCase.update(
                actor, lotId, workerId, "Limpieza", newSchedule, null);

        assertThat(r.outcome()).isEqualTo(UpdateWorkerUseCase.Outcome.UPDATED);
        assertThat(r.service()).isEqualTo("Limpieza");
        verify(workers).updateAttributes(workerId, lotId, "Limpieza", newSchedule);
    }

    @Test
    void workerInexistenteEn404() {
        when(workers.find(workerId, lotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.update(actor, lotId, workerId, null, null, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void patchVacioRechaza() {
        assertThatThrownBy(() -> useCase.update(actor, lotId, workerId, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serviceSinScheduleRechaza() {
        assertThatThrownBy(() -> useCase.update(actor, lotId, workerId, "Solo service", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
