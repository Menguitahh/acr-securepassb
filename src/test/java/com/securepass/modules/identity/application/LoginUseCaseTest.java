package com.securepass.modules.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.shared.security.JwtTokenProvider;
import com.securepass.shared.security.RefreshTokenGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LoginUseCaseTest {

    private static final String TEST_SECRET = "test-secret-that-is-long-enough-to-pass-hmac-32-byte-check";
    private static final String RAW_PASSWORD = "secret123";

    private UserRepository users;
    private UserProfileQuery profiles;
    private PasswordEncoder encoder;
    private JwtTokenProvider jwt;
    private RefreshTokenGenerator refresh;
    private RefreshTokenStore refreshStore;
    private LoginUseCase useCase;
    private UUID userId;
    private UUID neighborhoodId;
    private User activeUser;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(UserProfileQuery.class);
        encoder = new BCryptPasswordEncoder(4);
        jwt = new JwtTokenProvider(TEST_SECRET, 900, new ObjectMapper());
        refresh = new RefreshTokenGenerator();
        refreshStore = mock(RefreshTokenStore.class);
        useCase = new LoginUseCase(users, encoder, jwt, refresh, profiles, refreshStore);
        userId = UUID.randomUUID();
        neighborhoodId = UUID.randomUUID();
        activeUser = new User(userId, "juan@mail.com", null, encoder.encode(RAW_PASSWORD),
                "Juan Perez", "30123456", null, UserStatus.ACTIVE);
    }

    @Test
    void loginExitosoDevuelveTokensYPerfil() {
        when(users.findByIdentifier("juan@mail.com")).thenReturn(Optional.of(activeUser));
        when(profiles.summarize(userId)).thenReturn(new UserProfileSummary(
                neighborhoodId, "America/Argentina/Mendoza", List.of(Role.RESIDENT_OWNER), List.of()));

        LoginUseCase.Result result = useCase.login("juan@mail.com", RAW_PASSWORD);

        assertThat(result).isInstanceOf(LoginUseCase.Success.class);
        LoginUseCase.Success success = (LoginUseCase.Success) result;
        assertThat(success.userId()).isEqualTo(userId);
        assertThat(success.fullName()).isEqualTo("Juan Perez");
        assertThat(success.neighborhoodId()).isEqualTo(neighborhoodId);
        assertThat(success.neighborhoodTimezone()).isEqualTo("America/Argentina/Mendoza");
        assertThat(success.roles()).containsExactly(Role.RESIDENT_OWNER);
        assertThat(success.accessToken().split("\\.")).hasSize(3);
        assertThat(success.refreshToken()).isNotBlank();
        assertThat(success.expiresIn()).isEqualTo(900);
        verify(refreshStore).save(eq(success.refreshToken()), any(UUID.class), eq(userId));
    }

    @Test
    void trimmeaIdentifierAntesDeBuscar() {
        when(users.findByIdentifier("juan@mail.com")).thenReturn(Optional.of(activeUser));
        when(profiles.summarize(any())).thenReturn(new UserProfileSummary(null, null, List.of(), List.of()));

        useCase.login("  juan@mail.com  ", RAW_PASSWORD);

        verify(users).findByIdentifier("juan@mail.com");
    }

    @Test
    void usuarioInexistenteDevuelveInvalidCredentials() {
        when(users.findByIdentifier("nadie@mail.com")).thenReturn(Optional.empty());

        LoginUseCase.Result result = useCase.login("nadie@mail.com", "cualquiera");

        assertThat(result).isEqualTo(LoginUseCase.Failure.INVALID_CREDENTIALS);
        verifyNoInteractions(profiles);
    }

    @Test
    void passwordMalDevuelveInvalidCredentials() {
        when(users.findByIdentifier("juan@mail.com")).thenReturn(Optional.of(activeUser));

        LoginUseCase.Result result = useCase.login("juan@mail.com", "otra-password");

        assertThat(result).isEqualTo(LoginUseCase.Failure.INVALID_CREDENTIALS);
        verifyNoInteractions(profiles);
    }

    @Test
    void usuarioSuspendidoDevuelveInvalidCredentials() {
        User suspended = new User(userId, "juan@mail.com", null, encoder.encode(RAW_PASSWORD),
                "Juan Perez", null, null, UserStatus.SUSPENDED);
        when(users.findByIdentifier("juan@mail.com")).thenReturn(Optional.of(suspended));

        LoginUseCase.Result result = useCase.login("juan@mail.com", RAW_PASSWORD);

        assertThat(result).isEqualTo(LoginUseCase.Failure.INVALID_CREDENTIALS);
        verifyNoInteractions(profiles);
    }

    @Test
    void identifierVacioDevuelveInvalidCredentialsSinTocarRepo() {
        LoginUseCase.Result result = useCase.login("   ", RAW_PASSWORD);

        assertThat(result).isEqualTo(LoginUseCase.Failure.INVALID_CREDENTIALS);
        verifyNoInteractions(users, profiles);
    }

    @Test
    void passwordVaciaDevuelveInvalidCredentialsSinTocarRepo() {
        LoginUseCase.Result result = useCase.login("juan@mail.com", "");

        assertThat(result).isEqualTo(LoginUseCase.Failure.INVALID_CREDENTIALS);
        verifyNoInteractions(users, profiles);
    }
}
