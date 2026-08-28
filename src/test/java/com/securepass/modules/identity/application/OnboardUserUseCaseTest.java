package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.modules.neighborhood.application.AddLotMemberUseCase;
import com.securepass.modules.neighborhood.application.MembershipGranter;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.UserOnboardingConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OnboardUserUseCaseTest {

    private UserRepository users;
    private MembershipGranter memberships;
    private AddLotMemberUseCase addLotMember;
    private IssueActivationTokenUseCase issuer;
    private OnboardUserUseCase useCase;

    private UUID neighborhoodId;
    private UUID lotId;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        memberships = mock(MembershipGranter.class);
        addLotMember = mock(AddLotMemberUseCase.class);
        issuer = mock(IssueActivationTokenUseCase.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        SimpleTransactionStatus status = new SimpleTransactionStatus() {
            @Override public Object createSavepoint() { return new Object(); }
            @Override public void rollbackToSavepoint(Object savepoint) {}
            @Override public void releaseSavepoint(Object savepoint) {}
        };
        when(txManager.getTransaction(any())).thenReturn(status);
        useCase = new OnboardUserUseCase(users, memberships, addLotMember, issuer, txManager);
        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
    }

    // ---- creacion (DNI nuevo) ----

    @Test
    void residentOwnerHappyPathCreacion() {
        when(users.findByDocument("30111222")).thenReturn(Optional.empty());
        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "Owner@New.com", "  Owner Nuevo  ", "30111222", Role.RESIDENT_OWNER, lotId);

        OnboardUserUseCase.Result result = useCase.onboard(neighborhoodId, "Admin", "Barrio Test", cmd);

        assertThat(result.reused()).isFalse();
        assertThat(result.changed()).isTrue();
        assertThat(result.email()).isEqualTo("owner@new.com");
        assertThat(result.fullName()).isEqualTo("Owner Nuevo");
        assertThat(result.status()).isEqualTo(UserStatus.PENDING_ACTIVATION);

        ArgumentCaptor<User> userSaved = ArgumentCaptor.forClass(User.class);
        verify(users).create(userSaved.capture());
        assertThat(userSaved.getValue().email()).isEqualTo("owner@new.com");
        assertThat(userSaved.getValue().passwordHash()).isNull();

        verify(addLotMember).add(eq(neighborhoodId), eq(lotId),
                argThat(c -> c.userId().equals(result.userId()) && c.isOwner()));
        verify(memberships, never()).grant(any(), any(), any());
        verify(issuer).issue(eq(result.userId()), eq(TokenPurpose.ACTIVATION), eq("Admin"), eq("Barrio Test"));
    }

    @Test
    void guardHappyPathCreacion() {
        when(users.findByDocument("30111223")).thenReturn(Optional.empty());
        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "guard@new.com", "Guard Nuevo", "30111223", Role.GUARD, null);

        OnboardUserUseCase.Result result = useCase.onboard(neighborhoodId, "Admin", "Barrio", cmd);

        assertThat(result.reused()).isFalse();
        assertThat(result.changed()).isTrue();
        verify(users).create(any(User.class));
        verify(memberships).grant(result.userId(), neighborhoodId, Role.GUARD);
        verifyNoInteractions(addLotMember);
        verify(issuer).issue(eq(result.userId()), eq(TokenPurpose.ACTIVATION), any(), any());
    }

    // ---- validaciones de Command ----

    @Test
    void commandRechazaRolNoPermitido() {
        assertThatThrownBy(() -> new OnboardUserUseCase.Command(
                "a@b.c", "n", "30111222", Role.ADMIN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandRechazaResidentOwnerSinLot() {
        assertThatThrownBy(() -> new OnboardUserUseCase.Command(
                "a@b.c", "n", "30111222", Role.RESIDENT_OWNER, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandRechazaGuardConLot() {
        assertThatThrownBy(() -> new OnboardUserUseCase.Command(
                "a@b.c", "n", "30111222", Role.GUARD, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- WBS-107: reuso por DNI existente ----

    @Test
    void reusoActiveConPermisoNuevo() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "real@x.com", null, "hash", "Nombre Real", "30111222", null, UserStatus.ACTIVE)));
        when(addLotMember.isMemberOfLot(lotId, existing)).thenReturn(false);

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "otro@x.com", "Otro Nombre", "30111222", Role.RESIDENT_OWNER, lotId);
        OnboardUserUseCase.Result r = useCase.onboard(neighborhoodId, "Admin", "B", cmd);

        assertThat(r.userId()).isEqualTo(existing);
        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isTrue();          // se sumo permiso
        assertThat(r.email()).isEqualTo("real@x.com");  // gana el viejo
        assertThat(r.fullName()).isEqualTo("Nombre Real"); // no se pisa
        verify(users, never()).create(any());
        verify(users, never()).completeEmail(any(), any());
        verify(addLotMember).add(eq(neighborhoodId), eq(lotId), any());
        verify(issuer, never()).issue(any(), any(), any(), any()); // ACTIVE, no re-issue
    }

    @Test
    void reusoIdempotenteConPermisoYaExistente() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "real@x.com", null, "hash", "Nombre Real", "30111222", null, UserStatus.ACTIVE)));
        when(addLotMember.isMemberOfLot(lotId, existing)).thenReturn(true);

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "otro@x.com", "Otro", "30111222", Role.RESIDENT_OWNER, lotId);
        OnboardUserUseCase.Result r = useCase.onboard(neighborhoodId, "Admin", "B", cmd);

        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isFalse();
        verify(addLotMember, never()).add(any(), any(), any());
        verify(memberships, never()).grant(any(), any(), any());
        verify(issuer, never()).issue(any(), any(), any(), any());
    }

    @Test
    void reusoIdempotentePendingActivationNoReemiteToken() {
        // Doble alta sobre user PENDING con permiso ya existente:
        // changed=false y NO se reemite email de activacion.
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "e@x.com", null, null, "N", "30111222", null, UserStatus.PENDING_ACTIVATION)));
        when(addLotMember.isMemberOfLot(lotId, existing)).thenReturn(true);

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "e@x.com", "N", "30111222", Role.RESIDENT_OWNER, lotId);
        OnboardUserUseCase.Result r = useCase.onboard(neighborhoodId, "Admin", "B", cmd);

        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isFalse();
        verify(issuer, never()).issue(any(), any(), any(), any());
    }

    @Test
    void reusoGuardIdempotenteCuandoMembershipExiste() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111223")).thenReturn(Optional.of(new User(
                existing, "g@x.com", null, "hash", "G", "30111223", null, UserStatus.ACTIVE)));
        when(memberships.exists(existing, neighborhoodId, Role.GUARD)).thenReturn(true);

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "otro@x.com", "Otro", "30111223", Role.GUARD, null);
        OnboardUserUseCase.Result r = useCase.onboard(neighborhoodId, "Admin", "B", cmd);

        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isFalse();
        verify(memberships, never()).grant(any(), any(), any());
    }

    @Test
    void reusoEmailNullCompletaYReemite() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, null, "+549...", null, "N", "30111222", null, UserStatus.PENDING_ACTIVATION)));
        when(users.completeEmail(existing, "nuevo@x.com")).thenReturn(true);
        when(addLotMember.isMemberOfLot(lotId, existing)).thenReturn(false);

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "nuevo@x.com", "N", "30111222", Role.RESIDENT_OWNER, lotId);
        OnboardUserUseCase.Result r = useCase.onboard(neighborhoodId, "Admin", "B", cmd);

        assertThat(r.reused()).isTrue();
        assertThat(r.changed()).isTrue();
        assertThat(r.email()).isEqualTo("nuevo@x.com");
        verify(users).completeEmail(existing, "nuevo@x.com");
        verify(issuer).issue(eq(existing), eq(TokenPurpose.ACTIVATION), any(), any());
    }

    @Test
    void reusoPendingActivationReemiteToken() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "e@x.com", null, null, "N", "30111222", null, UserStatus.PENDING_ACTIVATION)));
        when(addLotMember.isMemberOfLot(lotId, existing)).thenReturn(false);

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "e@x.com", "N", "30111222", Role.RESIDENT_OWNER, lotId);
        OnboardUserUseCase.Result r = useCase.onboard(neighborhoodId, "Admin", "B", cmd);

        assertThat(r.reused()).isTrue();
        assertThat(r.status()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        verify(issuer).issue(eq(existing), eq(TokenPurpose.ACTIVATION), eq("Admin"), eq("B"));
    }

    @Test
    void suspended409ConCodigo() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "e@x.com", null, "hash", "N", "30111222", null, UserStatus.SUSPENDED)));

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "otro@x.com", "N", "30111222", Role.RESIDENT_OWNER, lotId);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOfSatisfying(UserOnboardingConflictException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("USER_SUSPENDED");
                    assertThat(ex.existingUserId()).isEqualTo(existing);
                });
        verifyNoInteractions(addLotMember, memberships, issuer);
    }

    @Test
    void deleted409ConCodigo() {
        UUID existing = UUID.randomUUID();
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "e@x.com", null, "hash", "N", "30111222", null, UserStatus.DELETED)));

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "otro@x.com", "N", "30111222", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOfSatisfying(UserOnboardingConflictException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("USER_DELETED");
                    assertThat(ex.existingUserId()).isEqualTo(existing);
                });
        verifyNoInteractions(memberships, issuer);
    }

    // ---- EMAIL_TAKEN (DNI nuevo pero email colisiona) ----

    @Test
    void emailTakenCuandoDniEsNuevoPeroEmailChoca() {
        UUID existingEmail = UUID.randomUUID();
        when(users.findByDocument("30111223")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("dup")).when(users).create(any());
        when(users.findByEmail("a@b.c")).thenReturn(Optional.of(new User(
                existingEmail, "a@b.c", null, "hash", "Otro", "30111222", null, UserStatus.ACTIVE)));

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111223", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOfSatisfying(UserOnboardingConflictException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("EMAIL_TAKEN");
                    assertThat(ex.existingUserId()).isEqualTo(existingEmail);
                });
        verifyNoInteractions(issuer);
    }

    @Test
    void rethrowSiConstraintNoEsIdentificable() {
        when(users.findByDocument(any())).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("weird")).when(users).create(any());
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111222", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void addLotMemberFalladoNoEmiteIssue() {
        when(users.findByDocument("30111222")).thenReturn(Optional.empty());
        doThrow(new ResourceNotFoundException("lote", lotId)).when(addLotMember).add(any(), any(), any());

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111222", Role.RESIDENT_OWNER, lotId);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(users).create(any(User.class));
        verifyNoInteractions(issuer);
    }
}
