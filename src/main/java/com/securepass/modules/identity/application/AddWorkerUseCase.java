package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.exception.UserOnboardingConflictException;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.CurrentUser;
import com.securepass.shared.security.WorkerPermission;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-115 / US-62: el titular habilita a un trabajador en su lote.
 *
 * Reusa el patron de {@link AddFamilyMemberUseCase}: DNI existente en el
 * barrio se reusa (misma persona en varios lotes = misma cuenta, mismo QR
 * cuando llegue WBS-116). DNI nuevo crea user PENDING_ACTIVATION y dispara
 * activacion. Sin edad minima: el trabajador es adulto por hipotesis del
 * dominio, y el spec no lo restringe.
 *
 * Gate: {@code @PreAuthorize("@lotPerm.hasAtLeast(#lotId, OWNER)")} en el
 * controller. Sin ADMIN: la administracion de trabajadores es del titular
 * (US-62 "el titular"), no del barrio.
 */
@Service
public class AddWorkerUseCase {

    private final UserRepository users;
    private final WorkerPermissionRepository workers;
    private final IssueActivationTokenUseCase activation;
    private final TransactionTemplate tx;

    public AddWorkerUseCase(
            UserRepository users,
            WorkerPermissionRepository workers,
            IssueActivationTokenUseCase activation,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.workers = workers;
        this.activation = activation;
        this.tx = new TransactionTemplate(txManager);
    }

    public Result add(CurrentUser actor, UUID lotId, Command cmd,
                      String actorFullName, String neighborhoodName) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        // securepassf#119: sin email no hay activacion; el trabajador entra por DNI.
        String email = blankToNull(cmd.email()) == null ? null : cmd.email().trim().toLowerCase(Locale.ROOT);
        String phone = blankToNull(cmd.phone()) == null ? null : cmd.phone().trim();
        String dni = cmd.dni().trim();

        return tx.execute(status -> {
            Optional<User> byDoc = users.findByDocument(dni);
            if (byDoc.isPresent()) {
                return reuseFlow(byDoc.get(), email, cmd, lotId, actorFullName, neighborhoodName);
            }
            return createFlow(email, phone, dni, cmd, lotId, actorFullName, neighborhoodName, status);
        });
    }

    private Result reuseFlow(User existing, String email, Command cmd, UUID lotId,
                             String actorFullName, String neighborhoodName) {
        switch (existing.status()) {
            case SUSPENDED -> throw new UserOnboardingConflictException(
                    "USER_SUSPENDED", existing.id(),
                    "El usuario esta suspendido; coordinar antes de darle acceso como trabajador");
            case DELETED -> throw new UserOnboardingConflictException(
                    "USER_DELETED", existing.id(),
                    "El usuario esta dado de baja");
            default -> {}
        }

        String finalEmail = existing.email();
        boolean emailCompleted = false;
        if ((finalEmail == null || finalEmail.isBlank()) && email != null) {
            emailCompleted = users.completeEmail(existing.id(), email);
            finalEmail = emailCompleted ? email :
                    users.findById(existing.id()).map(User::email).orElse(email);
        }

        Outcome outcome;
        boolean permissionChanged;
        Optional<WorkerPermission> existingPerm = workers.find(existing.id(), lotId);
        if (existingPerm.isPresent()) {
            if (existingPerm.get().active()) {
                outcome = Outcome.ALREADY_ACTIVE;
                permissionChanged = false;
            } else {
                workers.reactivate(existing.id(), lotId, cmd.service(), cmd.schedule());
                outcome = Outcome.REACTIVATED_PERMISSION;
                permissionChanged = true;
            }
        } else {
            workers.save(existing.id(), lotId, cmd.service(), cmd.schedule());
            outcome = Outcome.ADDED_PERMISSION;
            permissionChanged = true;
        }

        boolean changed = permissionChanged || emailCompleted;
        if (existing.status() == UserStatus.PENDING_ACTIVATION && changed
                && finalEmail != null && !finalEmail.isBlank()) {
            activation.issue(existing.id(), TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        }
        return new Result(existing.id(), existing.fullName(), finalEmail,
                cmd.service(), cmd.schedule(), true, true, changed, outcome, existing.status());
    }

    private Result createFlow(String email, String phone, String dni, Command cmd, UUID lotId,
                              String actorFullName, String neighborhoodName,
                              org.springframework.transaction.TransactionStatus status) {
        UUID newUserId = UUID.randomUUID();
        User user = new User(newUserId, email, phone, null, cmd.fullName().trim(),
                dni, null, UserStatus.PENDING_ACTIVATION);

        Object savepoint = status.createSavepoint();
        try {
            users.create(user);
            status.releaseSavepoint(savepoint);
        } catch (DataIntegrityViolationException dive) {
            status.rollbackToSavepoint(savepoint);
            throw translateConflict(dive, email, dni);
        }

        workers.save(newUserId, lotId, cmd.service(), cmd.schedule());
        if (email != null) activation.issue(newUserId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        return new Result(newUserId, cmd.fullName().trim(), email,
                cmd.service(), cmd.schedule(), false, true, true,
                Outcome.CREATED_USER_AND_PERMISSION, UserStatus.PENDING_ACTIVATION);
    }

    private UserOnboardingConflictException translateConflict(
            DataIntegrityViolationException dive, String email, String dni) {
        Optional<User> byEmail = email == null ? Optional.empty() : users.findByEmail(email);
        if (byEmail.isPresent()) {
            return new UserOnboardingConflictException(
                    "EMAIL_TAKEN", byEmail.get().id(),
                    "El email ya esta registrado con otro DNI");
        }
        Optional<User> byDoc = users.findByDocument(dni);
        if (byDoc.isPresent()) {
            return new UserOnboardingConflictException(
                    "USER_SUSPENDED", byDoc.get().id(),
                    "Race en el alta: reintentar");
        }
        throw dive;
    }

    public record Command(
            String fullName, String dni, String email, String phone,
            String service, ScheduleWindow schedule) {
        public Command {
            if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("fullName requerido");
            if (dni == null || dni.isBlank()) throw new IllegalArgumentException("dni requerido");
            if (blankToNull(email) == null && blankToNull(phone) == null) {
                throw new IllegalArgumentException("email o phone requerido");
            }
            if (service == null || service.isBlank()) throw new IllegalArgumentException("service requerido");
            if (schedule == null) throw new IllegalArgumentException("schedule requerido");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * WBS-115: outcome del alta. Mismos casos que familia pero enum propio —
     * el modulo trabajadores no depende del enum de familia.
     */
    public enum Outcome {
        CREATED_USER_AND_PERMISSION,
        ADDED_PERMISSION,
        REACTIVATED_PERMISSION,
        ALREADY_ACTIVE
    }

    public record Result(
            UUID userId, String fullName, String email,
            String service, ScheduleWindow schedule,
            boolean reused, boolean permissionActive, boolean changed,
            Outcome outcome, UserStatus userStatus) {}
}
