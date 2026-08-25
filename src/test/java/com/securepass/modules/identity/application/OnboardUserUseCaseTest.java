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
import org.springframework.transaction.TransactionStatus;
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
        // Devolvemos un TransactionStatus que soporta savepoints en memoria:
        // los tests no necesitan un tx real, solo ejecutar el bloque.
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

    @Test
    void residentOwnerHappyPath() {
        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "Owner@New.com", "  Owner Nuevo  ", "30111222", Role.RESIDENT_OWNER, lotId);

        UUID newUserId = useCase.onboard(neighborhoodId, "Admin", "Barrio Test", cmd);

        ArgumentCaptor<User> userSaved = ArgumentCaptor.forClass(User.class);
        verify(users).create(userSaved.capture());
        assertThat(userSaved.getValue().email()).isEqualTo("owner@new.com");
        assertThat(userSaved.getValue().fullName()).isEqualTo("Owner Nuevo");
        assertThat(userSaved.getValue().documentNumber()).isEqualTo("30111222");
        assertThat(userSaved.getValue().status()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(userSaved.getValue().passwordHash()).isNull();

        verify(addLotMember).add(eq(neighborhoodId), eq(lotId),
                argThat(c -> c.userId().equals(newUserId) && c.isOwner() && "OWNER".equals(c.relation())));
        verify(memberships, never()).grant(any(), any(), any());
        verify(issuer).issue(eq(newUserId), eq(TokenPurpose.ACTIVATION), eq("Admin"), eq("Barrio Test"));
    }

    @Test
    void guardHappyPath() {
        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "guard@new.com", "Guard Nuevo", "30111223", Role.GUARD, null);

        UUID newUserId = useCase.onboard(neighborhoodId, "Admin", "Barrio", cmd);

        verify(users).create(any(User.class));
        verify(memberships).grant(newUserId, neighborhoodId, Role.GUARD);
        verifyNoInteractions(addLotMember);
        verify(issuer).issue(eq(newUserId), eq(TokenPurpose.ACTIVATION), any(), any());
    }

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

    @Test
    void emailTakenSiExisteUsuarioActive() {
        UUID existing = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("dup")).when(users).create(any());
        when(users.findByEmail("a@b.c")).thenReturn(Optional.of(new User(
                existing, "a@b.c", null, "hash", "Otro", "30111222", UserStatus.ACTIVE)));

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111223", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOfSatisfying(UserOnboardingConflictException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("EMAIL_TAKEN");
                    assertThat(ex.existingUserId()).isNull();
                });
        verifyNoInteractions(issuer);
    }

    @Test
    void pendingActivationExistsSiElUsuarioYaEstaPending() {
        UUID existing = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("dup")).when(users).create(any());
        when(users.findByEmail("a@b.c")).thenReturn(Optional.of(new User(
                existing, "a@b.c", null, null, "Nuevo", "30111222", UserStatus.PENDING_ACTIVATION)));

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111223", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOfSatisfying(UserOnboardingConflictException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("PENDING_ACTIVATION_EXISTS");
                    assertThat(ex.existingUserId()).isEqualTo(existing);
                });
        verifyNoInteractions(issuer);
    }

    @Test
    void dniTakenSiElEmailEsNuevoPeroElDocumentoNo() {
        UUID existing = UUID.randomUUID();
        doThrow(new DataIntegrityViolationException("dup")).when(users).create(any());
        when(users.findByEmail("a@b.c")).thenReturn(Optional.empty());
        when(users.findByDocument("30111222")).thenReturn(Optional.of(new User(
                existing, "otro@x.com", null, "hash", "Otra", "30111222", UserStatus.ACTIVE)));

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111222", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOfSatisfying(UserOnboardingConflictException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("DNI_TAKEN");
                    assertThat(ex.existingUserId()).isEqualTo(existing);
                });
        verifyNoInteractions(issuer);
    }

    @Test
    void rethrowSiLaConstraintChocadaNoEsEmailNiDni() {
        doThrow(new DataIntegrityViolationException("weird")).when(users).create(any());
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByDocument(any())).thenReturn(Optional.empty());

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111222", Role.GUARD, null);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void noEmiteIssueSiAddLotMemberFalla() {
        doThrow(new ResourceNotFoundException("lote", lotId)).when(addLotMember).add(any(), any(), any());

        OnboardUserUseCase.Command cmd = new OnboardUserUseCase.Command(
                "a@b.c", "Nuevo", "30111222", Role.RESIDENT_OWNER, lotId);
        assertThatThrownBy(() -> useCase.onboard(neighborhoodId, "Admin", "B", cmd))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(users).create(any(User.class));
        verifyNoInteractions(issuer);
    }
}
