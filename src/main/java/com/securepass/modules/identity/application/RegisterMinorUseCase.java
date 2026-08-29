package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.Minor;
import com.securepass.shared.exception.AgeOverMaximumException;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * WBS-111 / US-58: alta de menor en el padron del lote.
 * El gate {@code @PreAuthorize(hasAtLeast(OWNER))} cubre el titular; aca
 * solo se hace el aislamiento por barrio (el lote pertenece al barrio del
 * actor) y las validaciones de dominio.
 */
@Service
public class RegisterMinorUseCase {

    private final MinorRepository minors;

    public RegisterMinorUseCase(MinorRepository minors) {
        this.minors = minors;
    }

    @Transactional
    public Minor register(CurrentUser actor, UUID lotId, Command cmd) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        // Aislamiento por barrio: el lote debe pertenecer al barrio del actor.
        // El gate garantiza que actor es OWNER de lotId; este check es la
        // ultima linea si el gate se mis-configura. Mismo criterio que
        // ListFamilyMembersUseCase (#135): 403 cross-barrio.
        UUID lotBarrio = minors.findLotNeighborhood(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!lotBarrio.equals(actor.neighborhoodId())) {
            throw new AccessDeniedException("recurso de otro barrio");
        }

        // Regla de negocio del alta (spec WBS-111 "menor de 16"). El invariante
        // del dominio permite hasta 17 para la ventana de graduacion; la
        // restriccion estricta la impone este use case.
        if (cmd.birthDate() != null
                && Period.between(cmd.birthDate(), LocalDate.now()).getYears() >= Minor.MAX_AGE_YEARS) {
            throw new AgeOverMaximumException(Minor.MAX_AGE_YEARS, lotId);
        }

        Minor minor;
        try {
            minor = new Minor(
                    UUID.randomUUID(), lotId, actor.neighborhoodId(),
                    cmd.fullName().trim(),
                    cmd.documentNumber() == null ? null : cmd.documentNumber().trim(),
                    cmd.birthDate(),
                    cmd.relation(),
                    cmd.notes(),
                    actor.userId(),
                    Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new BusinessRuleViolationException(
                    "minor-invalid", ex.getMessage() == null ? "invalido" : ex.getMessage());
        }

        try {
            minors.save(minor);
        } catch (DuplicateKeyException dup) {
            // uq_lot_minors_lot_document: mismo DNI en el mismo lote. Cuaderno
            // pesado: el titular cargo dos veces al mismo chico.
            throw new BusinessRuleViolationException(
                    "minor-duplicate", "ya hay un menor con ese DNI en el lote");
        }
        return minor;
    }

    public record Command(
            String fullName,
            String documentNumber,   // opcional
            LocalDate birthDate,
            String relation,
            String notes) {}
}
