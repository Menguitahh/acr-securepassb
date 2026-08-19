package com.securepass.modules.invitation.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private static Invitation single() {
        return single(1, 0);
    }

    private static Invitation single(int maxUses, int usedCount) {
        return new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Maria Lopez", "30123456", 2, "cumple",
                NOW, NOW.plus(Duration.ofHours(6)),
                null, null, null,
                maxUses, usedCount,
                "kid-1", UUID.randomUUID(), false, NOW);
    }

    private static Invitation event(int maxUses, int usedCount) {
        return new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.EVENT, InvitationStatus.ACTIVE,
                "Fiesta", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(1)),
                null, null, null,
                maxUses, usedCount,
                "kid-1", UUID.randomUUID(), false, NOW);
    }

    private static Invitation recurring(boolean residentPass) {
        return new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.RECURRING, InvitationStatus.ACTIVE,
                "Empleada", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(200)),
                List.of(1, 2, 3, 4, 5), LocalTime.of(8, 0), LocalTime.of(18, 0),
                residentPass ? 1 : 10, 0,
                "kid-1", UUID.randomUUID(), residentPass, NOW);
    }

    @Test
    void construyeSingleValida() {
        Invitation inv = single();
        assertThat(inv.status()).isEqualTo(InvitationStatus.ACTIVE);
        assertThat(inv.usedCount()).isZero();
    }

    @Test
    void rechazaValidUntilAnteriorAValidFrom() {
        assertThatThrownBy(() -> new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", null, 0, null,
                NOW, NOW.minusSeconds(1),
                null, null, null,
                1, 0, "kid-1", UUID.randomUUID(), false, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void singleForzaMaxUsesEnUno() {
        assertThatThrownBy(() -> single(2, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recurringExigeWeekdaysYFranja() {
        assertThatThrownBy(() -> new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.RECURRING, InvitationStatus.ACTIVE,
                "X", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(30)),
                null, null, null,
                1, 0, "kid-1", UUID.randomUUID(), false, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // BR-04: ventana <= 30 dias SINGLE/EVENT, <= 365 RECURRING
    @Test
    void br04SingleExcedeTreintaDiasFalla() {
        assertThatThrownBy(() -> new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(31)),
                null, null, null,
                1, 0, "kid-1", UUID.randomUUID(), false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-04");
    }

    @Test
    void br04EventExcedeTreintaDiasFalla() {
        assertThatThrownBy(() -> new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.EVENT, InvitationStatus.ACTIVE,
                "X", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(31)),
                null, null, null,
                10, 0, "kid-1", UUID.randomUUID(), false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-04");
    }

    @Test
    void br04RecurringAceptaTrescientosSesentaYCinco() {
        Invitation inv = new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.RECURRING, InvitationStatus.ACTIVE,
                "X", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(365)),
                List.of(1), LocalTime.of(8, 0), LocalTime.of(18, 0),
                10, 0, "kid-1", UUID.randomUUID(), false, NOW);
        assertThat(inv.type()).isEqualTo(InvitationType.RECURRING);
    }

    @Test
    void br04RecurringExcedeTrescientosSesentaYCincoFalla() {
        assertThatThrownBy(() -> new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.RECURRING, InvitationStatus.ACTIVE,
                "X", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(366)),
                List.of(1), LocalTime.of(8, 0), LocalTime.of(18, 0),
                10, 0, "kid-1", UUID.randomUUID(), false, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-04");
    }

    // BR-05: EVENT maxUses <= 200
    @Test
    void br05EventAceptaDoscientos() {
        Invitation inv = event(200, 0);
        assertThat(inv.maxUses()).isEqualTo(200);
    }

    @Test
    void br05EventExcedeDoscientosFalla() {
        assertThatThrownBy(() -> event(201, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-05");
    }

    // BR-06: usedCount solo incrementa en registerEntry (IN + ALLOWED)
    @Test
    void br06RegisterEntryIncrementaUsedCount() {
        Invitation inv = event(3, 0).registerEntry();
        assertThat(inv.usedCount()).isEqualTo(1);
        assertThat(inv.status()).isEqualTo(InvitationStatus.ACTIVE);
    }

    @Test
    void br06RegisterEntryAlLimiteMarcaUsed() {
        Invitation inv = event(2, 1).registerEntry();
        assertThat(inv.usedCount()).isEqualTo(2);
        assertThat(inv.status()).isEqualTo(InvitationStatus.USED);
    }

    // BR-10: residentPass no consume cupo
    @Test
    void br10ResidentPassNoIncrementa() {
        Invitation inv = recurring(true).registerEntry();
        assertThat(inv.usedCount()).isZero();
        assertThat(inv.status()).isEqualTo(InvitationStatus.ACTIVE);
    }

    @Test
    void br10ResidentPassExigeRecurring() {
        assertThatThrownBy(() -> new Invitation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "Residente", null, 0, null,
                NOW, NOW.plus(Duration.ofDays(1)),
                null, null, null,
                1, 0, "kid-1", UUID.randomUUID(), true, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Maquina de estados: transiciones permitidas y prohibidas
    @Test
    void revokeDesdeActive() {
        assertThat(single().revoke().status()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    void revokeDesdeUsed() {
        Invitation used = single().registerEntry();
        assertThat(used.status()).isEqualTo(InvitationStatus.USED);
        assertThat(used.revoke().status()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    void expireDesdeActive() {
        assertThat(single().expire().status()).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    void revokedNoPuedeExpirar() {
        Invitation revoked = single().revoke();
        assertThatThrownBy(revoked::expire).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiredNoPuedeRevocarse() {
        Invitation expired = single().expire();
        assertThatThrownBy(expired::revoke).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerEntryDesdeUsedFalla() {
        Invitation used = single().registerEntry();
        assertThatThrownBy(used::registerEntry).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerEntryDesdeRevokedFalla() {
        Invitation revoked = single().revoke();
        assertThatThrownBy(revoked::registerEntry).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registerEntryDesdeExpiredFalla() {
        Invitation expired = single().expire();
        assertThatThrownBy(expired::registerEntry).isInstanceOf(IllegalStateException.class);
    }
}
