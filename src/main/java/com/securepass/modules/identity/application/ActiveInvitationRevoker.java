package com.securepass.modules.identity.application;

import java.util.UUID;

public interface ActiveInvitationRevoker {

    /**
     * Revoca en cascada las invitaciones ACTIVE de {@code userId} en {@code lotId}
     * y devuelve la cantidad de filas afectadas para que el caller pueda
     * reportar "N pases revocados" al usuario.
     */
    int revokeActiveByLotAndUser(UUID lotId, UUID userId, UUID revokedBy);
}
