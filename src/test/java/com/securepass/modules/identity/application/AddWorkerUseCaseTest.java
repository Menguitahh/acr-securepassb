package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.UserOnboardingConflictException;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.CurrentUser;
import com.securepass.shared.security.WorkerPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

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

class AddWorkerUseCaseTest {

    private static final String ACTOR = "Titular Uno";
    private static final String NEIGHBORHOOD = "Barrio X";

    private UserRepository users;
    private WorkerPermissionRepository workers;
    private IssueActivationTokenUseCase activation;
    private AddWorkerUseCase useCase;

    private CurrentUser actor;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        workers = mock(WorkerPermissionRepository.class);
        activation = mock(IssueActivationTokenUseCase.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        useCase = new AddWorkerUseCase(users, workers, activation, txManager);

        UUID actorId = UUID.randomUUID();
        UUID neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        actor = new CurrentUser(actorId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));
    }

    private AddWorkerUseCase.Command cmd() {
        return new AddWorkerUseCase.Command(
                "Juan Jardinero", "30111222", "juan@dev.local", null,
                "Jardineria",
                new ScheduleWindow(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0)));
    }

    @Test
    void dniNuevoCreaUserYWorkerYDisparaActivacion() {
        when(users.findByDocument("30111222")).thenReturn(Optional.empty());

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.CREATED_USER_AND_PERMISSION);
        assertThat(r.reused()).isFalse();
        assertThat(r.changed()).isTrue();
        assertThat(r.userStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);

        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(users).create(userCap.capture());
        assertThat(userCap.getValue().documentNumber()).isEqualTo("30111222");
        assertThat(userCap.getValue().birthDate()).isNull();

        verify(workers).save(eq(userCap.getValue().id()), eq(lotId), eq("Jardineria"), any(ScheduleWindow.class));
        verify(activation).issue(userCap.getValue().id(), TokenPurpose.ACTIVATION, ACTOR, NEIGHBORHOOD);
    }

    // securepassf#119: sin email no hay activacion; entra por DNI en garita.
    @Test
    void altaSoloConTelefonoNoDisparaActivacion() {
        when(users.findByDocument("30111222")).thenReturn(Optional.empty());
        AddWorkerUseCase.Command sinEmail = new AddWorkerUseCase.Command(
                "Juan Jardinero", "30111222", " ", "2615550000", "Jardineria",
                new ScheduleWindow(List.of(2, 4), LocalTime.of(8, 0), LocalTime.of(12, 0)));

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, sinEmail, ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.CREATED_USER_AND_PERMISSION);
        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(users).create(userCap.capture());
        assertThat(userCap.getValue().email()).isNull();
        assertThat(userCap.getValue().phone()).isEqualTo("2615550000");
        verify(activation, never()).issue(any(), any(), anyString(), anyString());
    }

    @Test
    void sinEmailNiTelefonoNoSeConstruye() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AddWorkerUseCase.Command(
                "Juan", "30111222", null, "", "Jardineria",
                new ScheduleWindow(List.of(2), LocalTime.of(8, 0), LocalTime.of(12, 0))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dniExistenteSinPermisoEnEsteLoteSumaPermiso() {
        User existing = new User(
                UUID.randomUUID(), "juan@dev.local", null, "h", "Juan Jardinero",
                "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(workers.find(existing.id(), lotId)).thenReturn(Optional.empty());

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.ADDED_PERMISSION);
        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isTrue();
        verify(workers).save(eq(existing.id()), eq(lotId), eq("Jardineria"), any(ScheduleWindow.class));
        // user ACTIVE no dispara activacion
        verify(activation, never()).issue(any(), any(), anyString(), anyString());
    }

    @Test
    void dniExistenteConWorkerActivoEsIdempotente() {
        User existing = new User(
                UUID.randomUUID(), "juan@dev.local", null, "h", "Juan Jardinero",
                "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(workers.find(existing.id(), lotId)).thenReturn(Optional.of(new WorkerPermission(
                existing.id(), lotId, "Otra cosa",
                new ScheduleWindow(List.of(1), LocalTime.of(6, 0), LocalTime.of(7, 0)),
                true)));

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.ALREADY_ACTIVE);
        assertThat(r.changed()).isFalse();
        verify(workers, never()).save(any(), any(), anyString(), any());
        verify(workers, never()).reactivate(any(), any(), anyString(), any());
    }

    @Test
    void dniExistenteConWorkerInactivoLoReactiva() {
        User existing = new User(
                UUID.randomUUID(), "juan@dev.local", null, "h", "Juan Jardinero",
                "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(workers.find(existing.id(), lotId)).thenReturn(Optional.of(new WorkerPermission(
                existing.id(), lotId, "Vieja",
                new ScheduleWindow(List.of(1), LocalTime.of(6, 0), LocalTime.of(7, 0)),
                false)));

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.REACTIVATED_PERMISSION);
        assertThat(r.changed()).isTrue();
        verify(workers).reactivate(eq(existing.id()), eq(lotId), eq("Jardineria"), any(ScheduleWindow.class));
    }

    @Test
    void dniExistentePendingSumaPermisoYDisparaActivacion() {
        User existing = new User(
                UUID.randomUUID(), "juan@dev.local", null, null, "Juan Jardinero",
                "30111222", null, UserStatus.PENDING_ACTIVATION);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(users.findById(existing.id())).thenReturn(Optional.of(existing));
        when(workers.find(existing.id(), lotId)).thenReturn(Optional.empty());

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.ADDED_PERMISSION);
        assertThat(r.changed()).isTrue();
        verify(activation).issue(existing.id(), TokenPurpose.ACTIVATION, ACTOR, NEIGHBORHOOD);
    }

    @Test
    void userSuspendedRechaza() {
        User existing = new User(
                UUID.randomUUID(), "juan@dev.local", null, "h", "Juan Jardinero",
                "30111222", null, UserStatus.SUSPENDED);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD))
                .isInstanceOf(UserOnboardingConflictException.class)
                .extracting("code").isEqualTo("USER_SUSPENDED");
        verify(workers, never()).save(any(), any(), anyString(), any());
    }

    @Test
    void dniExistenteSinEmailLoCompleta() {
        User existing = new User(
                UUID.randomUUID(), null, "1150000000", "h", "Juan Jardinero",
                "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(users.completeEmail(existing.id(), "juan@dev.local")).thenReturn(true);
        when(workers.find(existing.id(), lotId)).thenReturn(Optional.empty());

        AddWorkerUseCase.Result r = useCase.add(actor, lotId, cmd(), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddWorkerUseCase.Outcome.ADDED_PERMISSION);
        assertThat(r.email()).isEqualTo("juan@dev.local");
        verify(users).completeEmail(existing.id(), "juan@dev.local");
    }
}
