package com.securepass.modules.identity.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogoutUseCaseTest {

    private RefreshTokenStore store;
    private LogoutUseCase useCase;

    @BeforeEach
    void setUp() {
        store = mock(RefreshTokenStore.class);
        useCase = new LogoutUseCase(store);
    }

    @Test
    void logoutValidoRevocaFamilia() {
        UUID familyId = UUID.randomUUID();
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.Consumed(familyId, UUID.randomUUID()));

        useCase.logout("raw");

        verify(store).revokeFamily(familyId);
    }

    @Test
    void logoutConReuseRevocaFamilia() {
        UUID familyId = UUID.randomUUID();
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.ReplayDetected(familyId));

        useCase.logout("raw");

        verify(store).revokeFamily(familyId);
    }

    @Test
    void logoutConTokenInvalidoNoHaceNada() {
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.Invalid());

        useCase.logout("raw");

        verify(store, never()).revokeFamily(any());
    }

    @Test
    void logoutSinTokenNoTocaStore() {
        useCase.logout(" ");
        verifyNoInteractions(store);
    }
}
