package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Minor;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * WBS-111: gradua un menor a familiar con cuenta.
 *
 * Operacion explicita (no automatica) al cumplir 16: el titular decide
 * cuando activar la cuenta. La transaccion:
 *   1) DELETE de la fila del padron.
 *   2) Delegacion a {@link AddFamilyMemberUseCase} con los datos del padron
 *      (fullName, dni, birthDate) mas los que aporta el titular (email,
 *      relation, canInvite).
 *
 * Todo en una transaccion: si el alta falla (SUSPENDED, DELETED, email
 * invalido, DNI ausente en el padron y no aportado, etc.), el DELETE se
 * revierte y el menor sigue en el padron. Test que fuerza el fallo:
 * {@code GraduateMinorRollbackTest.altaFallidaMantieneElMenorEnElPadron}.
 *
 * <p>DNI obligatorio: el padron lo permite opcional (chico de 5 sin DNI
 * todavia), pero WBS-110 lo exige. Si el menor del padron no tiene DNI
 * cargado, el {@code Command} pide {@code dniOverride}; si el padron ya
 * tiene DNI, se ignora (el del padron gana, para evitar que el titular
 * mande uno distinto por accidente).
 */
@AllArgsConstructor
@Service
public class GraduateMinorUseCase {

    private final MinorRepository minors;
    private final AddFamilyMemberUseCase addFamilyMember;

    @Transactional
    public Result graduate(
            CurrentUser actor, UUID lotId, UUID minorId, Command cmd,
            String actorFullName, String neighborhoodName) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (minorId == null) throw new IllegalArgumentException("minorId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        Minor minor = minors.findById(minorId)
                .orElseThrow(() -> new ResourceNotFoundException("minor", minorId));
        if (!minor.neighborhoodId().equals(actor.neighborhoodId())) {
            throw new AccessDeniedException("recurso de otro barrio");
        }
        if (!minor.lotId().equals(lotId)) {
            throw new ResourceNotFoundException("minor", minorId);
        }

        String dni = minor.documentNumber();
        if (dni == null || dni.isBlank()) {
            dni = cmd.dniOverride() == null ? null : cmd.dniOverride().trim();
            if (dni == null || dni.isBlank()) {
                // WBS-110 exige DNI y esta rama es la unica forma de terminar
                // sin uno. 422 explicito con codigo distinguible en vez del
                // 500 que tiraria WBS-110 aguas abajo.
                throw new BusinessRuleViolationException(
                        "minor-dni-required",
                        "el menor no tiene DNI en el padron; incluir dniOverride para graduar");
            }
        }

        // DELETE primero: si el alta despues explota, el @Transactional
        // revierte todo y la fila del padron vuelve.
        minors.delete(minorId);

        AddFamilyMemberUseCase.Command addCmd = new AddFamilyMemberUseCase.Command(
                minor.fullName(), dni, minor.birthDate(),
                cmd.email(), cmd.relation() != null ? cmd.relation() : minor.relation(),
                cmd.canInvite());
        AddFamilyMemberUseCase.Result added = addFamilyMember.add(
                actor, lotId, addCmd, actorFullName, neighborhoodName);

        return new Result(added, minorId);
    }

    public record Command(
            String email,
            String relation,
            boolean canInvite,
            String dniOverride) {}

    public record Result(AddFamilyMemberUseCase.Result added, UUID graduatedFromMinorId) {}
}
