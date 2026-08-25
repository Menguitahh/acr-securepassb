package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.modules.neighborhood.application.AddLotMemberUseCase;
import com.securepass.modules.neighborhood.application.MembershipGranter;
import com.securepass.shared.exception.UserOnboardingConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WBS-128: alta de usuario por admin del barrio. Todo en una transaccion:
 * user + membership + (vinculo al lote si RESIDENT_OWNER) + emision del
 * token de activacion. Si algo falla, el listener AFTER_COMMIT no dispara
 * y no queda ninguna fila.
 *
 * Los 409 salen de constraints de la base (email UNIQUE, document_number
 * UNIQUE partial) traducidas a UserOnboardingConflictException. Sin
 * pre-check con findByEmail: el pre-check tiene race entre el SELECT y
 * el INSERT si el admin hace doble click.
 */
@Service
public class OnboardUserUseCase {

    private static final Set<Role> ALLOWED_ROLES = EnumSet.of(Role.RESIDENT_OWNER, Role.GUARD);

    private final UserRepository users;
    private final MembershipGranter memberships;
    private final AddLotMemberUseCase addLotMember;
    private final IssueActivationTokenUseCase issuer;
    private final TransactionTemplate tx;

    public OnboardUserUseCase(
            UserRepository users,
            MembershipGranter memberships,
            AddLotMemberUseCase addLotMember,
            IssueActivationTokenUseCase issuer,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.memberships = memberships;
        this.addLotMember = addLotMember;
        this.issuer = issuer;
        this.tx = new TransactionTemplate(txManager);
    }

    public UUID onboard(UUID neighborhoodId, String actorFullName, String neighborhoodName, Command cmd) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");
        // Command ya valido rol permitido, forma email/dni y invariante rol+lote.

        String email = cmd.email().trim().toLowerCase(Locale.ROOT);
        UUID newUserId = UUID.randomUUID();
        User user = new User(newUserId, email, null, null, cmd.fullName().trim(),
                cmd.dni().trim(), UserStatus.PENDING_ACTIVATION);

        // Todo en una sola transaccion. El INSERT del user va dentro de un
        // SAVEPOINT: si dispara la constraint UNIQUE (email o document),
        // rollback del savepoint deja la conexion sana para consultar cual
        // constraint choco. La consulta post-DIE NO es un pre-check
        // (la constraint ya se evaluo, no hay race), es un lookup de
        // detalles para armar el codigo del 409.
        return tx.execute(status -> {
            Object savepoint = status.createSavepoint();
            try {
                users.create(user);
                status.releaseSavepoint(savepoint);
            } catch (DataIntegrityViolationException dive) {
                status.rollbackToSavepoint(savepoint);
                throw translateConflict(dive, email, cmd.dni().trim());
            }

            if (cmd.role() == Role.GUARD) {
                memberships.grant(newUserId, neighborhoodId, Role.GUARD);
            } else {
                // RESIDENT_OWNER: reusa AddLotMemberUseCase, que valida que el
                // lote exista y pertenezca al barrio (404 si no), impide dos
                // titulares por lote, crea family_member y otorga la membresia.
                // Si falla, la excepcion propaga y la tx entera se revierte.
                addLotMember.add(neighborhoodId, cmd.lotId(),
                        new AddLotMemberUseCase.Command(newUserId, "OWNER", true, true));
            }

            issuer.issue(newUserId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
            return newUserId;
        });
    }

    private UserOnboardingConflictException translateConflict(
            DataIntegrityViolationException dive, String email, String dni) {
        // Estrategia: consultar por email y por documento para saber cual
        // constraint choco. No hay race con INSERT: la constraint ya fue
        // evaluada, la lectura de aca en adelante es sobre el estado firme.
        Optional<User> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            User u = byEmail.get();
            if (u.status() == UserStatus.PENDING_ACTIVATION) {
                return new UserOnboardingConflictException(
                        "PENDING_ACTIVATION_EXISTS", u.id(),
                        "El email tiene un alta pendiente de activacion");
            }
            return new UserOnboardingConflictException(
                    "EMAIL_TAKEN", null, "El email ya esta registrado");
        }
        Optional<User> byDni = users.findByDocument(dni);
        if (byDni.isPresent()) {
            return new UserOnboardingConflictException(
                    "DNI_TAKEN", byDni.get().id(),
                    "El DNI ya esta registrado; vincula al usuario existente en vez de crear uno nuevo");
        }
        // Otra constraint (poco probable): re-lanzamos para que el handler
        // generico devuelva 500 en vez de un 409 mentiroso.
        throw dive;
    }

    public record Command(String email, String fullName, String dni, Role role, UUID lotId) {
        public Command {
            if (email == null || email.isBlank()) throw new IllegalArgumentException("email requerido");
            if (fullName == null || fullName.isBlank()) throw new IllegalArgumentException("fullName requerido");
            if (dni == null || dni.isBlank()) throw new IllegalArgumentException("dni requerido");
            if (role == null) throw new IllegalArgumentException("role requerido");
            if (!ALLOWED_ROLES.contains(role)) {
                throw new IllegalArgumentException("role no permitido: " + role);
            }
            if (role == Role.RESIDENT_OWNER && lotId == null) {
                throw new IllegalArgumentException("lotId requerido para RESIDENT_OWNER");
            }
            if (role == Role.GUARD && lotId != null) {
                throw new IllegalArgumentException("lotId no permitido para GUARD");
            }
        }
    }
}
