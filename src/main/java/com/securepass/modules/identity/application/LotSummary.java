package com.securepass.modules.identity.application;

import java.util.UUID;

public record LotSummary(UUID id, String code, boolean canInvite, boolean isOwner) {
    public LotSummary {
        if (id == null) throw new IllegalArgumentException("id requerido");
        if (code == null || code.isBlank()) throw new IllegalArgumentException("code requerido");
    }
}
