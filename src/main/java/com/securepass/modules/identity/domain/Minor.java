package com.securepass.modules.identity.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.UUID;

/**
 * WBS-111 / US-58: persona del lote sin cuenta. Cuaderno del titular,
 * no entidad de identidad — nada de esta clase interactua con QR, permisos
 * ni memberships.
 *
 * Invariantes en el constructor: edad {@code < HARD_MAX_AGE_YEARS} (17,
 * mismo colchon que el CHECK de la migracion); nombre no vacio; birthDate
 * no futura ni anterior a 1900. La cota de negocio "menor de 16" NO se
 * chequea aca: la impone {@link com.securepass.modules.identity.application.RegisterMinorUseCase}
 * en el alta. Motivo: cuando un chico cumple 16, el titular tiene una
 * ventana para graduarlo con {@link com.securepass.modules.identity.application.GraduateMinorUseCase}
 * y durante esa ventana la fila sigue existiendo en DB. Si el constructor
 * rechazara age >= 16, no podriamos rehidratar la fila para graduarla.
 * Ver comentario en V13.
 */
public record Minor(
        UUID id,
        UUID lotId,
        UUID neighborhoodId,
        String fullName,
        String documentNumber,
        LocalDate birthDate,
        String relation,
        String notes,
        UUID createdBy,
        Instant createdAt) {

    /** Cota de negocio para el alta (spec WBS-111 "menor de 16"). */
    public static final int MAX_AGE_YEARS = 16;
    /** Cota dura del dominio, matcheando el CHECK de V13 (colchon de un anio
     *  para la ventana de graduacion). */
    public static final int HARD_MAX_AGE_YEARS = 17;

    private static final int FULL_NAME_MAX = 140;
    private static final int DOCUMENT_MAX = 20;
    private static final int RELATION_MAX = 30;
    private static final int NOTES_MAX = 280;
    private static final LocalDate BIRTH_DATE_MIN = LocalDate.of(1900, 1, 1);

    public Minor {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (fullName == null || fullName.isBlank()) {
            throw new IllegalArgumentException("fullName requerido");
        }
        if (fullName.length() > FULL_NAME_MAX) {
            throw new IllegalArgumentException("fullName excede " + FULL_NAME_MAX);
        }
        if (documentNumber != null && documentNumber.length() > DOCUMENT_MAX) {
            throw new IllegalArgumentException("documentNumber excede " + DOCUMENT_MAX);
        }
        if (birthDate == null) throw new IllegalArgumentException("birthDate requerido");
        if (birthDate.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("birthDate en el futuro");
        }
        if (birthDate.isBefore(BIRTH_DATE_MIN)) {
            throw new IllegalArgumentException("birthDate anterior a " + BIRTH_DATE_MIN);
        }
        int age = Period.between(birthDate, LocalDate.now()).getYears();
        if (age >= HARD_MAX_AGE_YEARS) {
            throw new IllegalArgumentException(
                    "minor: birth_date implica edad >= " + HARD_MAX_AGE_YEARS
                    + " (fuera del CHECK de V13; probablemente dato corrupto)");
        }
        if (relation != null && relation.length() > RELATION_MAX) {
            throw new IllegalArgumentException("relation excede " + RELATION_MAX);
        }
        if (notes != null && notes.length() > NOTES_MAX) {
            throw new IllegalArgumentException("notes excede " + NOTES_MAX);
        }
        if (createdBy == null) throw new IllegalArgumentException("createdBy requerido");
        if (createdAt == null) throw new IllegalArgumentException("createdAt requerido");
    }
}
