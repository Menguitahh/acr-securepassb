package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.GuestEnrollment;

import java.util.Optional;
import java.util.UUID;

public interface GuestEnrollmentRepository {

    Optional<GuestEnrollment> findByInvitationId(UUID invitationId);

    /**
     * Inserta el enrolamiento. Si ya existe (retry en red intermitente),
     * devuelve el existente sin duplicar. Se apoya en `guest_enrollments.
     * UNIQUE (invitation_id)` de V4.
     */
    GuestEnrollment saveIfAbsent(GuestEnrollment enrollment);
}
