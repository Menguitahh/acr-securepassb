package com.securepass.shared.security;

import com.securepass.modules.identity.domain.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessGuardTest {

    private final AccessGuard guard = new AccessGuard();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rechazaSinAutenticacion() {
        assertThatThrownBy(() -> guard.assertSameNeighborhood(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void rechazaBarrioDistinto() {
        UUID barrioA = UUID.randomUUID();
        UUID barrioB = UUID.randomUUID();
        authenticate(new CurrentUser(UUID.randomUUID(), barrioA, List.of(Role.RESIDENT_OWNER), List.of()));

        assertThatThrownBy(() -> guard.assertSameNeighborhood(barrioB))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aceptaMismoBarrio() {
        UUID nid = UUID.randomUUID();
        authenticate(new CurrentUser(UUID.randomUUID(), nid, List.of(Role.ADMIN), List.of()));

        assertThatCode(() -> guard.assertSameNeighborhood(nid)).doesNotThrowAnyException();
    }

    @Test
    void rechazaSiCurrentSinBarrio() {
        authenticate(new CurrentUser(UUID.randomUUID(), null, List.of(), List.of()));
        assertThatThrownBy(() -> guard.assertSameNeighborhood(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void requireDevuelveUsuarioAutenticado() {
        UUID uid = UUID.randomUUID();
        authenticate(new CurrentUser(uid, UUID.randomUUID(), List.of(Role.GUARD), List.of()));

        assertThat(guard.require().userId()).isEqualTo(uid);
    }

    @Test
    void assertOwnsLotRechazaSiNoEstaEnLaLista() {
        authenticate(new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), List.of(Role.RESIDENT_MEMBER), List.of(UUID.randomUUID())));

        assertThatThrownBy(() -> guard.assertOwnsLot(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void assertOwnsLotAceptaSiEstaEnLaLista() {
        UUID lot = UUID.randomUUID();
        authenticate(new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), List.of(Role.RESIDENT_OWNER), List.of(lot)));

        assertThatCode(() -> guard.assertOwnsLot(lot)).doesNotThrowAnyException();
    }

    private static void authenticate(CurrentUser user) {
        var auth = new UsernamePasswordAuthenticationToken(user, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
