package com.securepass.modules.invitation.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListRevokedInvitationsUseCaseTest {

    private RevokedTokensQuery query;
    private ListRevokedInvitationsUseCase useCase;
    private UUID neighborhoodId;

    @BeforeEach
    void setUp() {
        query = mock(RevokedTokensQuery.class);
        useCase = new ListRevokedInvitationsUseCase(query);
        neighborhoodId = UUID.randomUUID();
    }

    private CurrentUser guard() {
        return new CurrentUser(UUID.randomUUID(), neighborhoodId, List.of(Role.GUARD), List.of());
    }

    @Test
    void devuelveRevocadosDelBarrio() {
        Instant since = Instant.parse("2026-08-19T00:00:00Z");
        RevokedEntry entry = new RevokedEntry(UUID.randomUUID(), Instant.now());
        when(query.listRevoked(eq(neighborhoodId), eq(since), any(Integer.class)))
                .thenReturn(List.of(entry));

        List<RevokedEntry> result = useCase.list(guard(), since);

        assertThat(result).containsExactly(entry);
    }

    @Test
    void sinSinceDelegaConNull() {
        when(query.listRevoked(eq(neighborhoodId), eq(null), any(Integer.class)))
                .thenReturn(List.of());

        useCase.list(guard(), null);

        verify(query).listRevoked(eq(neighborhoodId), eq(null), any(Integer.class));
    }

    @Test
    void usuarioSinBarrioRechaza() {
        CurrentUser actor = new CurrentUser(UUID.randomUUID(), null, List.of(Role.GUARD), List.of());

        assertThatThrownBy(() -> useCase.list(actor, null))
                .isInstanceOf(AccessDeniedException.class);
    }
}
