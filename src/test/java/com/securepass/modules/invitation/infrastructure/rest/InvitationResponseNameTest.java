package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.InvitationPage;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InvitationResponseNameTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");

    @Test
    void detalleExponeNeighborhoodName() {
        Invitation inv = invitation();
        InvitationResponse resp = InvitationResponse.from(inv, "Barrio Los Alamos", "SP1.tok", "https://x/i/abc");

        assertThat(resp.neighborhoodId()).isEqualTo(inv.neighborhoodId());
        assertThat(resp.neighborhoodName()).isEqualTo("Barrio Los Alamos");
    }

    @Test
    void listadoRellenaNeighborhoodNamePorPagina() {
        Invitation a = invitation();
        Invitation b = invitation();
        InvitationPage page = new InvitationPage(List.of(a, b), 0, 20, 2);

        PagedInvitationsResponse resp = PagedInvitationsResponse.from(page, id -> "Barrio Los Alamos");

        assertThat(resp.items()).extracting(InvitationResponse::neighborhoodName)
                .containsExactly("Barrio Los Alamos", "Barrio Los Alamos");
    }

    private Invitation invitation() {
        return new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 0, null,
                NOW.minusSeconds(60), NOW.plusSeconds(3600),
                null, null, null, 1, 0, "kid-1", UUID.randomUUID(), false, NOW);
    }
}
