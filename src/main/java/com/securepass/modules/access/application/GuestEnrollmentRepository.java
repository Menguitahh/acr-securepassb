package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.GuestEnrollment;

import java.util.Optional;
import java.util.UUID;

public interface GuestEnrollmentRepository {

    /**
     * WBS-122: la verificacion vive contra (barrio, documento), no contra la
     * invitacion. Una persona verificada una vez no vuelve a mostrar DNI, ni
     * en invitaciones posteriores ni en el flujo del trabajador.
     */
    Optional<GuestEnrollment> findByPerson(UUID neighborhoodId, String documentNumber);

    /**
     * Inserta el enrolamiento. Si ya existe la persona en el barrio (retry o
     * dos ingresos casi simultaneos), devuelve el existente sin duplicar. Se
     * apoya en {@code uq_enrollment_person_neighborhood} de V16.
     */
    GuestEnrollment saveIfAbsent(GuestEnrollment enrollment);
}
