package com.securepass.modules.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.security.JwtTokenProvider;
import com.securepass.shared.security.RefreshTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenUseCaseTest {

    private static final String TEST_SECRET = "test-secret-that-is-long-enough-to-pass-hmac-32-byte-check";

    private RefreshTokenStore store;
    private UserRepository users;
    private UserProfileQuery profiles;
    private JwtTokenProvider jwt;
    private RefreshTokenGenerator generator;
    private RefreshTokenUseCase useCase;
    private UUID userId;
    private UUID familyId;
    private User activeUser;

    @BeforeEach
    void setUp() {
        store = mock(RefreshTokenStore.class);
        users = mock(UserRepository.class);
        profiles = mock(UserProfileQuery.class);
        jwt = new JwtTokenProvider(TEST_SECRET, 900, new ObjectMapper());
        generator = new RefreshTokenGenerator();
        useCase = new RefreshTokenUseCase(store, users, profiles, jwt, generator);
        userId = UUID.randomUUID();
        familyId = UUID.randomUUID();
        activeUser = new User(userId, "a@b.com", null, "hash-irrelevante", "Ana", null, null, UserStatus.ACTIVE);
    }

    @Test
    void refreshExitosoRotaTokenYPreservaFamilia() {
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.Consumed(familyId, userId));
        when(users.findById(userId)).thenReturn(Optional.of(activeUser));
        UUID nid = UUID.randomUUID();
        when(profiles.summarize(userId)).thenReturn(
                new UserProfileSummary(nid, "America/Argentina/Mendoza", List.of(Role.RESIDENT_OWNER), List.of()));

        RefreshTokenUseCase.Result result = useCase.refresh("raw");

        assertThat(result).isInstanceOf(RefreshTokenUseCase.Success.class);
        RefreshTokenUseCase.Success success = (RefreshTokenUseCase.Success) result;
        assertThat(success.accessToken().split("\\.")).hasSize(3);
        assertThat(success.refreshToken()).isNotBlank();
        assertThat(success.userId()).isEqualTo(userId);
        assertThat(success.neighborhoodId()).isEqualTo(nid);
        assertThat(success.neighborhoodTimezone()).isEqualTo("America/Argentina/Mendoza");
        assertThat(success.roles()).containsExactly(Role.RESIDENT_OWNER);
        verify(store).save(eq(success.refreshToken()), eq(familyId), eq(userId));
        verify(store, never()).revokeFamily(any());
    }

    @Test
    void reusoDetectadoRevocaFamiliaYFalla() {
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.ReplayDetected(familyId));

        RefreshTokenUseCase.Result result = useCase.refresh("raw");

        assertThat(result).isEqualTo(RefreshTokenUseCase.Failure.INVALID_REFRESH);
        verify(store).revokeFamily(familyId);
    }

    @Test
    void refreshDesconocidoFallaSinRevocar() {
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.Invalid());

        RefreshTokenUseCase.Result result = useCase.refresh("raw");

        assertThat(result).isEqualTo(RefreshTokenUseCase.Failure.INVALID_REFRESH);
        verify(store, never()).revokeFamily(any());
    }

    @Test
    void refreshVacioFalla() {
        RefreshTokenUseCase.Result result = useCase.refresh(" ");

        assertThat(result).isEqualTo(RefreshTokenUseCase.Failure.INVALID_REFRESH);
    }

    @Test
    void usuarioInexistenteRevocaFamiliaYFalla() {
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.Consumed(familyId, userId));
        when(users.findById(userId)).thenReturn(Optional.empty());

        RefreshTokenUseCase.Result result = useCase.refresh("raw");

        assertThat(result).isEqualTo(RefreshTokenUseCase.Failure.INVALID_REFRESH);
        verify(store).revokeFamily(familyId);
    }

    @Test
    void usuarioSuspendidoRevocaFamiliaYFalla() {
        User suspended = new User(userId, "a@b.com", null, "hash", "Ana", null, null, UserStatus.SUSPENDED);
        when(store.consume("raw")).thenReturn(new RefreshTokenStore.Consumed(familyId, userId));
        when(users.findById(userId)).thenReturn(Optional.of(suspended));

        RefreshTokenUseCase.Result result = useCase.refresh("raw");

        assertThat(result).isEqualTo(RefreshTokenUseCase.Failure.INVALID_REFRESH);
        verify(store).revokeFamily(familyId);
    }
}
