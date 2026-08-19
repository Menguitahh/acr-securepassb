package com.securepass.modules.identity.application;

import org.springframework.stereotype.Service;

@Service
public class LogoutUseCase {

    private final RefreshTokenStore store;

    public LogoutUseCase(RefreshTokenStore store) {
        this.store = store;
    }

    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) return;
        RefreshTokenStore.ConsumeResult consume = store.consume(rawRefreshToken);
        if (consume instanceof RefreshTokenStore.Consumed c) {
            store.revokeFamily(c.familyId());
            return;
        }
        if (consume instanceof RefreshTokenStore.ReplayDetected r) {
            store.revokeFamily(r.familyId());
        }
    }
}
