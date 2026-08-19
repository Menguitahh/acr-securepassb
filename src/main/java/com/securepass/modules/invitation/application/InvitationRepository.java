package com.securepass.modules.invitation.application;

import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository {

    void save(Invitation invitation);

    void updateStatus(UUID id, InvitationStatus status, UUID revokedBy);

    Optional<Invitation> findById(UUID id);

    InvitationPage list(ListQuery query);

    record ListQuery(
            UUID neighborhoodId,
            List<UUID> lotIdsAllowed,
            UUID lotIdFilter,
            InvitationStatus statusFilter,
            int page,
            int size
    ) {
        public ListQuery {
            if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
            if (page < 0) throw new IllegalArgumentException("page >= 0");
            if (size < 1 || size > 100) throw new IllegalArgumentException("size 1..100");
            lotIdsAllowed = lotIdsAllowed == null ? List.of() : List.copyOf(lotIdsAllowed);
        }
    }
}
