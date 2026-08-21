package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.Stay;

import java.util.Optional;
import java.util.UUID;

public interface StayRepository {

    Optional<Stay> findOpenByInvitationId(UUID invitationId);

    Optional<Stay> findById(UUID id);

    /**
     * Inserta el stay abierto. Si ya hay uno abierto para la misma invitacion,
     * devuelve el existente (garantizado por `uq_open_stay_per_invitation`).
     */
    Stay openIfNotOpen(Stay stay);

    void close(Stay closedStay);

    java.util.List<OpenStayView> listOpenByNeighborhood(java.util.UUID neighborhoodId);

    void markResidentNotified(UUID stayId, java.time.Instant at);

    java.util.Optional<java.time.Instant> findResidentNotifiedAt(UUID stayId);

    void updateExpectedUntilByInvitation(UUID invitationId, java.time.Instant expectedUntil);
}
