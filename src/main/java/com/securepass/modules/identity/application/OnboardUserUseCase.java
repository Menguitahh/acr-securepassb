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
 * WBS-128: alta de usuario por admin del barrio.
 * WBS-107: si el DNI ya existe, reusa el user y suma el permiso en vez de
 * fallar. Regla de email de US-56: gana el email existente si lo hay; si
 * es null, se completa con el nuevo. Nombre no se pisa nunca.
 *
 * Estados del user existente:
 *   ACTIVE, PENDING_ACTIVATION -> reuso, suma permiso, reemite activacion
 *                                 si estaba PENDING (el issuer revoca el
 *                                 token viejo).
 *   SUSPENDED, DELETED         -> 409 explicito. No se reactiva un user
 *                                 marcado desde otro barrio sin coordinar.
 *
 * Todo en una transaccion. Si algo falla, ni user ni permiso ni token.
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

    public Result onboard(UUID neighborhoodId, String actorFullName, String neighborhoodName, Command cmd) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        String email = cmd.email().trim().toLowerCase(Locale.ROOT);
        String dni = cmd.dni().trim();

        return tx.execute(status -> {
            Optional<User> byDoc = users.findByDocument(dni);
            if (byDoc.isPresent()) {
                return reuseFlow(byDoc.get(), email, cmd, neighborhoodId, actorFullName, neighborhoodName);
            }
            return createFlow(email, dni, cmd, neighborhoodId, actorFullName, neighborhoodName, status);
        });
    }

    private Result reuseFlow(User existing, String email, Command cmd, UUID neighborhoodId,
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

        // Email: gana el viejo si lo tiene; se completa si es null.
        // Complete PRIMERO — issuer.issue() requiere email non-null.
        String finalEmail = existing.email();
        boolean emailCompleted = false;
        if (finalEmail == null || finalEmail.isBlank()) {
            emailCompleted = users.completeEmail(existing.id(), email);
            if (emailCompleted) {
                finalEmail = email;
            } else {
                // Race: otro proceso lo lleno; releer para devolver el real.
                finalEmail = users.findById(existing.id()).map(User::email).orElse(email);
            }
        }

        // Sumar permiso — pre-check para saber si cambio o no (idempotencia).
        boolean permissionChanged;
        if (cmd.role() == Role.GUARD) {
            if (memberships.exists(existing.id(), neighborhoodId, Role.GUARD)) {
                permissionChanged = false;
            } else {
                memberships.grant(existing.id(), neighborhoodId, Role.GUARD);
                permissionChanged = true;
            }
        } else {
            if (addLotMember.isMemberOfLot(cmd.lotId(), existing.id())) {
                permissionChanged = false;
            } else {
                // addLotMember.add() tira StateConflictException si el lote ya
                // tiene otro titular. Ese error propaga: es un conflicto real,
                // no idempotencia.
                addLotMember.add(neighborhoodId, cmd.lotId(),
                        new AddLotMemberUseCase.Command(existing.id(), "OWNER", true, true));
                permissionChanged = true;
            }
        }

        // Reemitir activacion solo si el reuso trajo cambio real (permiso
        // nuevo o email completado). Sin eso, un doble alta idempotente
        // seguiria mandando emails al user, y changed dejaria de reflejar
        // "algo cambio en el sistema".
        boolean changed = permissionChanged || emailCompleted;
        if (existing.status() == UserStatus.PENDING_ACTIVATION && changed) {
            issuer.issue(existing.id(), TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        }
        return new Result(existing.id(), cmd.role(), existing.status(), true, changed,
                finalEmail, existing.fullName());
    }

    private Result createFlow(String email, String dni, Command cmd, UUID neighborhoodId,
                              String actorFullName, String neighborhoodName,
                              org.springframework.transaction.TransactionStatus status) {
        UUID newUserId = UUID.randomUUID();
        User user = new User(newUserId, email, null, null, cmd.fullName().trim(),
                dni, null, UserStatus.PENDING_ACTIVATION);

        // SAVEPOINT alrededor del INSERT: si dispara UNIQUE (email — el DNI
        // ya lo pre-chequeamos arriba con findByDocument, aunque puede haber
        // race extremo de doble alta simultanea del mismo DNI), rollback del
        // savepoint deja la conexion sana para consultar el detalle.
        Object savepoint = status.createSavepoint();
        try {
            users.create(user);
            status.releaseSavepoint(savepoint);
        } catch (DataIntegrityViolationException dive) {
            status.rollbackToSavepoint(savepoint);
            throw translateConflict(dive, email, dni);
        }

        if (cmd.role() == Role.GUARD) {
            memberships.grant(newUserId, neighborhoodId, Role.GUARD);
        } else {
            addLotMember.add(neighborhoodId, cmd.lotId(),
                    new AddLotMemberUseCase.Command(newUserId, "OWNER", true, true));
        }

        issuer.issue(newUserId, TokenPurpose.ACTIVATION, actorFullName, neighborhoodName);
        return new Result(newUserId, cmd.role(), UserStatus.PENDING_ACTIVATION, false, true,
                email, cmd.fullName().trim());
    }

    private UserOnboardingConflictException translateConflict(
            DataIntegrityViolationException dive, String email, String dni) {
        Optional<User> byEmail = users.findByEmail(email);
        if (byEmail.isPresent()) {
            return new UserOnboardingConflictException(
                    "EMAIL_TAKEN", byEmail.get().id(),
                    "El email ya esta registrado con otro DNI");
        }
        // Race muy raro: alguien creo un user con el mismo DNI entre nuestro
        // findByDocument y el INSERT. Re-tratamos como reuso mirando de nuevo.
        Optional<User> byDoc = users.findByDocument(dni);
        if (byDoc.isPresent()) {
            return new UserOnboardingConflictException(
                    "USER_SUSPENDED", byDoc.get().id(),
                    "Race en el alta: reintentar");
        }
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

    /**
     * WBS-107: shape unificado creacion vs reuso. El frontend no ramifica
     * por status HTTP para saber que campos leer.
     *   reused=false, changed=true  -> user creado, permiso creado, token emitido.
     *   reused=true,  changed=true  -> user existente; se sumo permiso, se
     *                                  completo email, o se reemitio token.
     *   reused=true,  changed=false -> user existente y permiso ya existia
     *                                  con estos parametros; nada cambio.
     */
    public record Result(UUID userId, Role role, UserStatus status, boolean reused,
                         boolean changed, String email, String fullName) {}
}
