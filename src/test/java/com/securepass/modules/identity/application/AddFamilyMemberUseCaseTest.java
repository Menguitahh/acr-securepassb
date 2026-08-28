package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Membership;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.AgeUnderMinimumException;
import com.securepass.shared.exception.EmailRequiredException;
import com.securepass.shared.exception.UserOnboardingConflictException;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AddFamilyMemberUseCaseTest {

    private static final String ACTOR = "Owner Uno";
    private static final String NEIGHBORHOOD = "Barrio X";

    private UserRepository users;
    private FamilyMemberRepository family;
    private IssueActivationTokenUseCase activation;
    private AddFamilyMemberUseCase useCase;
    private CurrentUser actor;
    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        family = mock(FamilyMemberRepository.class);
        activation = mock(IssueActivationTokenUseCase.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);
        useCase = new AddFamilyMemberUseCase(users, family, activation, txManager);

        UUID actorId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        actor = new CurrentUser(actorId, neighborhoodId, List.of(Role.RESIDENT_OWNER), List.of(lotId));

        when(family.findMembership(any(), eq(neighborhoodId), eq(Role.RESIDENT_MEMBER)))
                .thenReturn(Optional.empty());
    }

    private AddFamilyMemberUseCase.Command cmd(String email, boolean canInvite, LocalDate birth, String relation) {
        return new AddFamilyMemberUseCase.Command(
                "Maria Lopez", "30111222", birth, email, relation, canInvite);
    }

    private LocalDate ageYears(int years) {
        return LocalDate.now().minusYears(years).minusDays(1);
    }

    @Test
    void dniNuevoCreaUserPermisoMembresiaYActivacion() {
        when(users.findByDocument("30111222")).thenReturn(Optional.empty());

        AddFamilyMemberUseCase.Result r = useCase.add(
                actor, lotId, cmd("maria@dev.local", true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddFamilyMemberUseCase.Outcome.CREATED_USER_AND_PERMISSION);
        assertThat(r.reused()).isFalse();
        assertThat(r.changed()).isTrue();
        assertThat(r.email()).isEqualTo("maria@dev.local");
        assertThat(r.canInvite()).isTrue();
        assertThat(r.userStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);

        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(users).create(userCap.capture());
        assertThat(userCap.getValue().documentNumber()).isEqualTo("30111222");
        assertThat(userCap.getValue().birthDate()).isNotNull();
        ArgumentCaptor<FamilyMember> permCap = ArgumentCaptor.forClass(FamilyMember.class);
        verify(family).save(permCap.capture());
        assertThat(permCap.getValue().isOwner()).isFalse();
        assertThat(permCap.getValue().canInvite()).isTrue();
        assertThat(permCap.getValue().relation()).isEqualTo("Hija");
        verify(family).saveMembership(any(Membership.class));
        verify(activation).issue(userCap.getValue().id(), TokenPurpose.ACTIVATION, ACTOR, NEIGHBORHOOD);
    }

    @Test
    void dniExistenteSinPermisoSumaPermisoActivo() {
        User existing = new User(UUID.randomUUID(), "maria@dev.local", null, "hash",
                "Maria", "30111222", ageYears(30), UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(family.find(existing.id(), lotId)).thenReturn(Optional.empty());

        AddFamilyMemberUseCase.Result r = useCase.add(
                actor, lotId, cmd("otra@dev.local", true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddFamilyMemberUseCase.Outcome.ADDED_PERMISSION);
        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isTrue();
        assertThat(r.email()).isEqualTo("maria@dev.local");
        verify(family).save(any(FamilyMember.class));
        verify(family, never()).reactivate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(activation, never()).issue(any(), any(), any(), any());
    }

    @Test
    void dniExistenteConPermisoActivoEsIdempotente() {
        User existing = new User(UUID.randomUUID(), "maria@dev.local", null, "hash",
                "Maria", "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(family.find(existing.id(), lotId))
                .thenReturn(Optional.of(new FamilyMember(existing.id(), lotId, "Hija", false, true, true)));

        AddFamilyMemberUseCase.Result r = useCase.add(
                actor, lotId, cmd("otra@dev.local", false, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddFamilyMemberUseCase.Outcome.ALREADY_MEMBER);
        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isFalse();
        verify(family, never()).save(any(FamilyMember.class));
        verify(family, never()).reactivate(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
        verify(activation, never()).issue(any(), any(), any(), any());
    }

    @Test
    void dniExistenteConPermisoInactivoReactiva() {
        User existing = new User(UUID.randomUUID(), "maria@dev.local", null, "hash",
                "Maria", "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(family.find(existing.id(), lotId))
                .thenReturn(Optional.of(new FamilyMember(existing.id(), lotId, "Hija", false, false, false)));

        AddFamilyMemberUseCase.Result r = useCase.add(
                actor, lotId, cmd("otra@dev.local", true, ageYears(30), "Yerno"), ACTOR, NEIGHBORHOOD);

        assertThat(r.outcome()).isEqualTo(AddFamilyMemberUseCase.Outcome.REACTIVATED_PERMISSION);
        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isTrue();
        verify(family).reactivate(existing.id(), lotId, true, "Yerno");
        verify(family, never()).save(any(FamilyMember.class));
    }

    @Test
    void dniExistenteEnOtroBarrioAgregaMembresia() {
        User existing = new User(UUID.randomUUID(), "maria@dev.local", null, "hash",
                "Maria", "30111222", null, UserStatus.ACTIVE);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(family.find(existing.id(), lotId)).thenReturn(Optional.empty());
        when(family.findMembership(existing.id(), neighborhoodId, Role.RESIDENT_MEMBER))
                .thenReturn(Optional.empty());

        useCase.add(actor, lotId, cmd("otra@dev.local", true, ageYears(30), null), ACTOR, NEIGHBORHOOD);

        ArgumentCaptor<Membership> cap = ArgumentCaptor.forClass(Membership.class);
        verify(family).saveMembership(cap.capture());
        assertThat(cap.getValue().neighborhoodId()).isEqualTo(neighborhoodId);
        assertThat(cap.getValue().role()).isEqualTo(Role.RESIDENT_MEMBER);
    }

    @Test
    void menorDeDieciseisEsRechazadoAntesDeConsultarDB() {
        assertThatThrownBy(() -> useCase.add(
                actor, lotId, cmd("kid@dev.local", true, ageYears(15), "Hijo"), ACTOR, NEIGHBORHOOD))
                .isInstanceOf(AgeUnderMinimumException.class)
                .satisfies(ex -> {
                    AgeUnderMinimumException e = (AgeUnderMinimumException) ex;
                    assertThat(e.minAge()).isEqualTo(16);
                    assertThat(e.lotId()).isEqualTo(lotId);
                });

        verify(users, never()).findByDocument(any());
        verify(family, never()).save(any(FamilyMember.class));
    }

    @Test
    void mayorSinEmailEsRechazado() {
        assertThatThrownBy(() -> useCase.add(
                actor, lotId, cmd(null, true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD))
                .isInstanceOf(EmailRequiredException.class);
        assertThatThrownBy(() -> useCase.add(
                actor, lotId, cmd("   ", true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD))
                .isInstanceOf(EmailRequiredException.class);

        verify(users, never()).findByDocument(any());
    }

    @Test
    void dniExistenteSuspendidoRechaza() {
        User existing = new User(UUID.randomUUID(), "maria@dev.local", null, "hash",
                "Maria", "30111222", null, UserStatus.SUSPENDED);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.add(
                actor, lotId, cmd("otra@dev.local", true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD))
                .isInstanceOf(UserOnboardingConflictException.class)
                .satisfies(ex -> {
                    UserOnboardingConflictException e = (UserOnboardingConflictException) ex;
                    assertThat(e.code()).isEqualTo("USER_SUSPENDED");
                    assertThat(e.existingUserId()).isEqualTo(existing.id());
                });
        verify(family, never()).save(any(FamilyMember.class));
    }

    @Test
    void dniExistenteEliminadoRechaza() {
        User existing = new User(UUID.randomUUID(), "maria@dev.local", null, "hash",
                "Maria", "30111222", null, UserStatus.DELETED);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> useCase.add(
                actor, lotId, cmd("otra@dev.local", true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD))
                .isInstanceOf(UserOnboardingConflictException.class)
                .satisfies(ex -> assertThat(((UserOnboardingConflictException) ex).code()).isEqualTo("USER_DELETED"));
    }

    @Test
    void dniExistentePendingCompletaEmailYReemiteActivacion() {
        // El user existente arranca sin email; el invariante de User exige
        // email o phone, asi que le damos phone para poder construirlo.
        User existing = new User(UUID.randomUUID(), null, "+5492611111111", null,
                "Maria", "30111222", null, UserStatus.PENDING_ACTIVATION);
        when(users.findByDocument("30111222")).thenReturn(Optional.of(existing));
        when(family.find(existing.id(), lotId)).thenReturn(Optional.empty());
        when(users.completeEmail(existing.id(), "maria@dev.local")).thenReturn(true);

        AddFamilyMemberUseCase.Result r = useCase.add(
                actor, lotId, cmd("maria@dev.local", true, ageYears(30), "Hija"), ACTOR, NEIGHBORHOOD);

        assertThat(r.email()).isEqualTo("maria@dev.local");
        assertThat(r.changed()).isTrue();
        verify(activation).issue(existing.id(), TokenPurpose.ACTIVATION, ACTOR, NEIGHBORHOOD);
    }
}
