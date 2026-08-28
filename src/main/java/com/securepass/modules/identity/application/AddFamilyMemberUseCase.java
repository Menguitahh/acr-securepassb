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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-110 / US-57: el titular agrega un familiar a su lote por DNI.
 *
 * Difiere del alta admin ({@link OnboardUserUseCase}) en tres cosas:
 *   1) permiso otorgado es MEMBER (no OWNER); membresia del barrio es
 *      RESIDENT_MEMBER.
 *   2) valida edad >= 16 antes de mirar la DB; menor -> AgeUnderMinimum
 *      con {@code redirectTo} al padron de menores (WBS-111).
 *   3) si el permiso existe INACTIVE en el lote, lo reactiva (con nuevos
 *      canInvite y relation) en vez de duplicar; el response distingue
 *      REACTIVATED_PERMISSION para que el frontend avise al titular.
 *
 * El gate por {@code @PreAuthorize("@lotPerm.hasAtLeast(#lotId, OWNER)")}
 * cubre que solo el titular llegue: no re-validamos aca.
 */
@Service
public class AddFamilyMemberUseCase {

    static final int MIN_AGE = 16;

    private final UserRepository users;
    private final FamilyMemberRepository family;
    private final IssueActivationTokenUseCase activation;
    private final TransactionTemplate tx;

    public AddFamilyMemberUseCase(
            UserRepository users,
            FamilyMemberRepository family,
            IssueActivationTokenUseCase activation,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.family = family;
        this.activation = activation;
        this.tx = new TransactionTemplate(txManager);
    }

    public Result add(CurrentUser actor, UUID lotId, Command cmd, String actorFullName, String neighborhoodName) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        int age = Period.between(cmd.birthDate(), LocalDate.now()).getYears();
        if (age < MIN_AGE) {
            throw new AgeUnderMinimumException(MIN_AGE, lotId);
        }
        String email = cmd.email() == null ? null : cmd.email().trim().toLowerCase(Locale.ROOT);
        if (email == null || email.isBlank()) {
            throw new EmailRequiredException();
        }

        String dni = cmd.dni().trim();
        UUID neighborhoodId = actor.neighborhoodId();

        return tx.execute(status -> {
            Optional<User> byDoc = users.findByDocument(dni);
            if (byDoc.isPresent()) {
                return reuseFlow(byDoc.get(), email, cmd, lotId, neighborhoodId, actorFullName, neighborhoodName);
            }
            return createFlow(email, dni, cmd, lotId, neighborhoodId, actorFullName, neighborhoodName, status);
        });
    }

    private Result reuseFlow(User existing, String email, Command cmd, UUID lotId, UUID neighborhoodId,
                             String actorFullName, String neighborhoodName) {
        switch (existing.status()) {
            case SUSPENDED -> throw new UserOnboardingConflictException(
                    "USER_SUSPENDED", existing.id(),
                    "El usuario esta suspendido; coordinar con quien lo suspendio antes de darle acceso");
            case DELETED -> throw new UserOnboardingConflictException(
                    "USER_DELETED", existing.id(),
                    "El usuario esta dado de baja");
            default -> {} // ACTIVE o PENDING_ACTIVATION: continua
        }

        // Regla de email US-56: gana el existente si lo tiene; se completa si null.
        String finalEmail = existing.email();
        boolean emailCompleted = false;
        if (finalEmail == null || finalEmail.isBlank()) {
            emailCompleted = users.completeEmail(existing.id(), email);
            finalEmail = emailCompleted ? email :
                    users.findById(existing.id()).map(User::email).orElse(email);
        }

        Outcome outcome;
        boolean permissionChanged;
        Optional<FamilyMember> existingPerm = family.find(existing.id(), lotId);
        if (existingPerm.isPresent()) {
            FamilyMember perm = existingPerm.get();
            if (perm.isOwner()) {
                // El DNI corresponde al mismo titular del lote. Idempotencia:
                // el gate ya lo dejo pasar como OWNER, no hay accion que sumar.
                outcome = Outcome.ALREADY_MEMBER;
                permissionChanged = false;
            } else if (perm.active()) {
                outcome = Outcome.ALREADY_MEMBER;
                permissionChanged = false;
            } else {
                family.reactivate(existing.id(), lotId, cmd.canInvite(), cmd.relation());
                outcome = Outcome.REACTIVATED_PERMISSION;
                permissionChanged = true;
            }
        } else {
            family.save(new FamilyMember(existing.id(), lotId, cmd.relation(),
                    false, cmd.canInvite(), true));
            outcome = Outcome.ADDED_PERMISSION;
            permissionChanged = true;
        }

        ensureResidentMembership(existing.id(), neighborhoodId);

        boolean changed = permissionChanged || emailCompleted;
        if (existing.status() == UserStatus.PENDING_ACTIVATION && changed) {
            activation.issue(existing.id(), TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        }
        return new Result(existing.id(), existing.fullName(), finalEmail, cmd.relation(),
                cmd.canInvite(), true, true, changed, outcome, existing.status());
    }

    private Result createFlow(String email, String dni, Command cmd, UUID lotId, UUID neighborhoodId,
                              String actorFullName, String neighborhoodName,
                              org.springframework.transaction.TransactionStatus status) {
        UUID newUserId = UUID.randomUUID();
        User user = new User(newUserId, email, null, null, cmd.fullName().trim(),
                dni, cmd.birthDate(), UserStatus.PENDING_ACTIVATION);

        Object savepoint = status.createSavepoint();
        try {
            users.create(user);
            status.releaseSavepoint(savepoint);
        } catch (DataIntegrityViolationException dive) {
            status.rollbackToSavepoint(savepoint);
            throw translateConflict(dive, email, dni);
        }

        family.save(new FamilyMember(newUserId, lotId, cmd.relation(), false, cmd.canInvite(), true));
        ensureResidentMembership(newUserId, neighborhoodId);

        activation.issue(newUserId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        return new Result(newUserId, cmd.fullName().trim(), email, cmd.relation(),
                cmd.canInvite(), true, false, true,
                Outcome.CREATED_USER_AND_PERMISSION, UserStatus.PENDING_ACTIVATION);
    }

    private void ensureResidentMembership(UUID userId, UUID neighborhoodId) {
        if (family.findMembership(userId, neighborhoodId, Role.RESIDENT_MEMBER).isPresent()) {
            return;
        }
        family.saveMembership(new Membership(
                UUID.randomUUID(), userId, neighborhoodId, Role.RESIDENT_MEMBER, true));
    }

    private UserOnboardingConflictException translateConflict(
            DataIntegrityViolationException dive, String email, String dni) {
        Optional<User> byEmail = users.findByEmail(email);
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
            String fullName, String dni, LocalDate birthDate,
            String email, String relation, boolean canInvite) {
        public Command {
            if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("fullName requerido");
            if (dni == null || dni.isBlank()) throw new IllegalArgumentException("dni requerido");
            if (birthDate == null) throw new IllegalArgumentException("birthDate requerido");
        }
    }

    /**
     * WBS-110: enum de outcome sobre reused/changed. Esto le permite al mobile
     * decirle al titular "reactivaste a alguien que estaba dado de baja" en
     * vez de "agregaste un miembro nuevo" (que era el mensaje generico y
     * enganoso).
     */
    public enum Outcome {
        CREATED_USER_AND_PERMISSION,
        ADDED_PERMISSION,
        REACTIVATED_PERMISSION,
        ALREADY_MEMBER
    }

    public record Result(
            UUID userId, String fullName, String email, String relation,
            boolean canInvite, boolean active,
            boolean reused, boolean changed,
            Outcome outcome, UserStatus userStatus) {}
}
