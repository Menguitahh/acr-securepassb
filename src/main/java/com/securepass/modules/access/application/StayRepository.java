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

    /**
     * Marca la stay abierta de esta invitacion como "salida solicitada" por el
     * residente. Idempotente: si ya estaba marcada, conserva el timestamp
     * original y devuelve ese valor. Devuelve `empty` si no hay stay abierta.
     */
    Optional<java.time.Instant> requestExitByInvitation(UUID invitationId, UUID requestedBy, java.time.Instant at);
}
