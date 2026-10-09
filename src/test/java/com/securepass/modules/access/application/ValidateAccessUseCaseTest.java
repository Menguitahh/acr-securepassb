package com.securepass.modules.access.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepass.modules.access.domain.AccessDecision;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.LotStatus;
import com.securepass.modules.invitation.application.InvitationRepository;
import com.securepass.modules.invitation.application.QrTokenCodec;
import com.securepass.modules.invitation.application.QrTokenPayload;
import com.securepass.modules.invitation.application.SigningKeyRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.invitation.domain.SigningKey;
import com.securepass.shared.crypto.Ed25519KeyFactory;
import com.securepass.shared.crypto.Ed25519KeyPair;
import com.securepass.shared.crypto.Ed25519Signer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ValidateAccessUseCaseTest {

    private static final Instant NOW = Instant.parse("2026-08-20T15:00:00Z");
    private static final ZoneId TZ = ZoneId.of("America/Argentina/Mendoza");

    private QrTokenCodec codec;
    private SigningKeyRepository signingKeys;
    private InvitationRepository invitations;
    private LotStatusQuery lots;
    private GuestEnrollmentRepository enrollments;
    private com.securepass.modules.invitation.application.WorkerTokenRepository workerTokens;
    private com.securepass.modules.invitation.application.WorkerValidationQuery workerQuery;
    private ValidateAccessUseCase useCase;

    private UUID neighborhoodId;
    private UUID lotId;
    private UUID invitationId;
    private UUID createdBy;
    private UUID jti;
    private Ed25519KeyPair keyPair;
    private SigningKey signingKey;

    @BeforeEach
    void setUp() {
        codec = new QrTokenCodec(new ObjectMapper());
        signingKeys = mock(SigningKeyRepository.class);
        invitations = mock(InvitationRepository.class);
        lots = mock(LotStatusQuery.class);
        enrollments = mock(GuestEnrollmentRepository.class);
        workerTokens = mock(com.securepass.modules.invitation.application.WorkerTokenRepository.class);
        workerQuery = mock(com.securepass.modules.invitation.application.WorkerValidationQuery.class);
        useCase = new ValidateAccessUseCase(codec, signingKeys, invitations, lots, enrollments, workerTokens, workerQuery);
        when(enrollments.findByPerson(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.of(
                new com.securepass.modules.access.domain.GuestEnrollment(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "30123456",
                        Instant.parse("2026-08-01T00:00:00Z"), null, null)));

        neighborhoodId = UUID.randomUUID();
        lotId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        createdBy = UUID.randomUUID();
        jti = UUID.randomUUID();
        keyPair = Ed25519KeyFactory.generate();
        signingKey = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                keyPair.publicKey(), "enc-privada", true, NOW, null);
    }

    private String signedToken(QrTokenPayload payload) {
        byte[] bytes = codec.serialize(payload);
        byte[] sig = Ed25519Signer.sign(keyPair.privateKey(), bytes);
        return codec.encode(payload, sig);
    }

    private QrTokenPayload payload(Instant validFrom, Instant validUntil, int maxUses, InvitationType type) {
        return new QrTokenPayload(1, "kid-1", jti, neighborhoodId,
                null, null,
                lotId, invitationId,
                type.code(), validFrom.getEpochSecond(), validUntil.getEpochSecond(), maxUses);
    }

    private Invitation singleInvitation(InvitationStatus status, int usedCount) {
        Instant from = NOW.minus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        return new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, status,
                "Maria Lopez", "30123456", 2, null,
                from, until,
                1, usedCount, "kid-1", jti, false, NOW);
    }

    private LotSnapshot activeLot() {
        return new LotSnapshot(lotId, "M4-L12", LotStatus.ACTIVE, TZ);
    }

    private void stubHappyPath(Invitation inv) {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(lots.find(lotId)).thenReturn(Optional.of(activeLot()));
    }

    @Test
    void allowedConSingleValida() {
        Instant from = NOW.minus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
        AccessDecision.Allowed allowed = (AccessDecision.Allowed) decision;
        assertThat(allowed.invitationId()).isEqualTo(invitationId);
        assertThat(allowed.jti()).isEqualTo(jti);
        assertThat(allowed.lotCode()).isEqualTo("M4-L12");
        assertThat(allowed.companions()).isEqualTo(2);
    }

    @Test
    void invalidSignatureCuandoTokenNoParsea() {
        AccessDecision decision = useCase.validate(neighborhoodId, "no-es-un-token", NOW, com.securepass.modules.access.domain.Direction.IN, null);
        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void invalidSignatureCuandoKidDesconocido() {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.empty());
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void invalidSignatureCuandoFirmaAdulterada() {
        Ed25519KeyPair otro = Ed25519KeyFactory.generate();
        SigningKey wrongKey = new SigningKey(
                UUID.randomUUID(), neighborhoodId, "kid-1",
                otro.publicKey(), "enc", true, NOW, null);
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(wrongKey));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void unknownCuandoInvitationNoExiste() {
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.empty());
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN));
    }

    @Test
    void invalidSignatureCuandoJtiNoCoincide() {
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)),
                1, 0, "kid-1", UUID.randomUUID(), false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    @Test
    void revokedCuandoStatusEsRevoked() {
        stubHappyPath(singleInvitation(InvitationStatus.REVOKED, 0));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.REVOKED, invitationId));
    }

    @Test
    void lotSuspendedCuandoLoteSuspendido() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(lots.find(lotId)).thenReturn(Optional.of(new LotSnapshot(lotId, "M4-L12", LotStatus.SUSPENDED, TZ)));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.LOT_SUSPENDED, invitationId));
    }

    // BR-12: sin enrolamiento, un IN a una invitacion de invitado devuelve NEEDS_ENROLLMENT.
    @Test
    void needsEnrollmentSiNoHayEnrolamientoYEsIN() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        stubHappyPath(inv);
        when(enrollments.findByPerson(org.mockito.ArgumentMatchers.eq(neighborhoodId), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isInstanceOf(AccessDecision.NeedsEnrollment.class);
        AccessDecision.NeedsEnrollment needs = (AccessDecision.NeedsEnrollment) decision;
        assertThat(needs.invitationId()).isEqualTo(invitationId);
        assertThat(needs.lotCode()).isEqualTo("M4-L12");
        // Issue #173: viaja el nombre para que el guardia lo cotele contra el
        // documento antes de confirmar el enroll.
        assertThat(needs.subjectName()).isEqualTo("Maria Lopez");
    }

    // BR-13: con enrolamiento existente, un IN pasa directo a ALLOWED.
    @Test
    void allowedConEnrolamientoExistente() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        stubHappyPath(inv);
        // el mock global ya devuelve enrolamiento presente
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    // Un OUT no requiere enrolamiento.
    @Test
    void allowedOutSinEnrolamiento() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        stubHappyPath(inv);
        when(enrollments.findByPerson(org.mockito.ArgumentMatchers.eq(neighborhoodId), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.OUT, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    // El residentPass no enrola (US-16). IN pasa directo aunque no haya enrolamiento.
    @Test
    void residentPassNoRequiereEnrolamiento() {
        Instant from = NOW.minus(Duration.ofDays(1));
        Instant until = NOW.plus(Duration.ofDays(30));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.EVENT, InvitationStatus.ACTIVE,
                "Residente", "00000000", 0, null,
                from, until,
                1, 0, "kid-1", jti, true, NOW);
        stubHappyPath(inv);
        when(enrollments.findByPerson(org.mockito.ArgumentMatchers.eq(neighborhoodId), org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        String token = signedToken(payload(from, until, 1, InvitationType.EVENT));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    // US-08 / BR-10: el residente entra aunque su lote este SUSPENDED.
    @Test
    void allowedResidentPassEnLoteSuspendido() {
        Instant from = NOW.minus(Duration.ofDays(1));
        Instant until = NOW.plus(Duration.ofDays(30));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.EVENT, InvitationStatus.ACTIVE,
                "Residente", "00000000", 0, null,
                from, until,
                1, 0, "kid-1", jti, true, NOW);
        when(signingKeys.findByKid(neighborhoodId, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        when(lots.find(lotId)).thenReturn(Optional.of(new LotSnapshot(lotId, "M4-L12", LotStatus.SUSPENDED, TZ)));
        String token = signedToken(payload(from, until, 1, InvitationType.EVENT));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    @Test
    void notYetValidCuandoCapturaAntesDeValidFrom() {
        Instant from = NOW.plus(Duration.ofHours(1));
        Instant until = NOW.plus(Duration.ofHours(6));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                from, until,
                1, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.NOT_YET_VALID, invitationId));
    }

    @Test
    void expiredCuandoCapturaDespuesDeValidUntil() {
        Instant from = NOW.minus(Duration.ofHours(6));
        Instant until = NOW.minus(Duration.ofHours(1));
        Invitation inv = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.ACTIVE,
                "X", "30123456", 0, null,
                from, until,
                1, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inv);
        String token = signedToken(payload(from, until, 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.EXPIRED, invitationId));
    }

    @Test
    void expiredCuandoStatusEsExpired() {
        Invitation inv = singleInvitation(InvitationStatus.EXPIRED, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.EXPIRED, invitationId));
    }

    @Test
    void alreadyUsedCuandoUsedCountAlcanzaMaxUses() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.ALREADY_USED, invitationId));
    }

    @Test
    void alreadyUsedCuandoStatusEsUsed() {
        Invitation inv = singleInvitation(InvitationStatus.USED, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.ALREADY_USED, invitationId));
    }

    @Test
    void outAllowedAunConInvitacionYaUsada() {
        // BR-06: egresos no consumen cupo. Un pase con usedCount == maxUses
        // sigue permitiendo el egreso.
        Invitation inv = singleInvitation(InvitationStatus.USED, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.OUT, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    // BR-07: la salida no se bloquea. Antes un pase revocado no podia salir:
    // el residente revoca justo para que la visita se vaya.
    @Test
    void outAllowedAunconInvitacionRevocada() {
        Invitation inv = singleInvitation(InvitationStatus.REVOKED, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.OUT, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    // BR-07: la visita que se paso de hora tiene que poder salir con su QR.
    @Test
    void outAllowedAunConPaseVencido() {
        Invitation inv = singleInvitation(InvitationStatus.EXPIRED, 1);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(5)), NOW.minus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.OUT, null);

        assertThat(decision).isInstanceOf(AccessDecision.Allowed.class);
    }

    @Test
    void outConFirmaInvalidaSigueDenegado() {
        AccessDecision decision = useCase.validate(neighborhoodId, "SP1.basura", NOW,
                com.securepass.modules.access.domain.Direction.OUT, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
    }

    // Fail-closed: si un status inconsistente esquiva todas las guardas
    // especificas, el default es Denied(UNKNOWN), nunca Allowed.
    @Test
    void defaultUnknownCuandoStatusInconsistenteEsquivaGuardas() {
        Invitation inconsistente = new Invitation(
                invitationId, neighborhoodId, lotId, createdBy,
                InvitationType.SINGLE, InvitationStatus.USED,
                "X", "30123456", 0, null,
                NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)),
                1, 0, "kid-1", jti, false, NOW);
        stubHappyPath(inconsistente);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)), 1, InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(neighborhoodId, token, NOW, com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN, invitationId));
    }

    // #211, primera barrera: la clave se busca con el barrio del guardia. El
    // QR es de neighborhoodId; el guardia es de otro barrio y ahi no hay kid-1.
    @Test
    void qrDeOtroBarrioNoEncuentraClaveEnElDelGuardia() {
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        stubHappyPath(inv);
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)), 1,
                InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(UUID.randomUUID(), token, NOW,
                com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.INVALID_SIGNATURE));
        verify(invitations, never()).findById(any());
    }

    // #211, segunda barrera: aunque la clave verifique en el barrio del
    // guardia, la invitacion de otro barrio sale UNKNOWN sin leer su lote.
    @Test
    void invitacionDeOtroBarrioSaleUnknownAunqueLaClaveVerifique() {
        UUID otroBarrio = UUID.randomUUID();
        Invitation inv = singleInvitation(InvitationStatus.ACTIVE, 0);
        when(signingKeys.findByKid(otroBarrio, "kid-1")).thenReturn(Optional.of(signingKey));
        when(invitations.findById(invitationId)).thenReturn(Optional.of(inv));
        String token = signedToken(payload(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(6)), 1,
                InvitationType.SINGLE));

        AccessDecision decision = useCase.validate(otroBarrio, token, NOW,
                com.securepass.modules.access.domain.Direction.IN, null);

        assertThat(decision).isEqualTo(AccessDecision.Denied.of(DenialReason.UNKNOWN));
        verify(lots, never()).find(any());
    }
}
