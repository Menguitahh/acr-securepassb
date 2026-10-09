package com.securepass.modules.notification.application;

import com.securepass.modules.invitation.application.ResidentDecisionMadeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotifyGuardsOfResidentDecisionUseCaseTest {

    private ResidentDecisionContextQuery contexts;
    private OpenGuardsAtGateQuery openGuards;
    private PushSender push;
    private NotifyGuardsOfResidentDecisionUseCase useCase;

    private UUID neighborhoodId;
    private UUID invitationId;
    private UUID stayId;
    private UUID gateId;

    @BeforeEach
    void setUp() {
        contexts = mock(ResidentDecisionContextQuery.class);
        openGuards = mock(OpenGuardsAtGateQuery.class);
        push = mock(PushSender.class);
        useCase = new NotifyGuardsOfResidentDecisionUseCase(contexts, openGuards, push);
        neighborhoodId = UUID.randomUUID();
        invitationId = UUID.randomUUID();
        stayId = UUID.randomUUID();
        gateId = UUID.randomUUID();
    }

    private ResidentDecisionContextQuery.Context context() {
        return new ResidentDecisionContextQuery.Context(
                stayId, gateId, "Juan Perez", "Ana Lopez",
                Instant.parse("2026-09-09T22:45:00Z"));
    }

    @Test
    void extendedDisparaPushAtodosLosGuardiasEnTurno() {
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        when(contexts.find(invitationId)).thenReturn(Optional.of(context()));
        when(openGuards.findGuardUserIds(neighborhoodId, gateId)).thenReturn(List.of(g1, g2));

        useCase.onResidentDecisionMade(new ResidentDecisionMadeEvent(
                neighborhoodId, invitationId, ResidentDecisionMadeEvent.Kind.EXTENDED));

        ArgumentCaptor<PushSender.GuardResidentDecision> payload =
                ArgumentCaptor.forClass(PushSender.GuardResidentDecision.class);
        verify(push, times(2)).notifyGuardOfResidentDecision(any(UUID.class), payload.capture());
        PushSender.GuardResidentDecision p = payload.getValue();
        assertThat(p.kind()).isEqualTo(PushSender.GuardResidentDecision.Kind.EXTENDED);
        assertThat(p.subjectName()).isEqualTo("Juan Perez");
        assertThat(p.residentName()).isEqualTo("Ana Lopez");
        assertThat(p.stayId()).isEqualTo(stayId);
        assertThat(p.invitationId()).isEqualTo(invitationId);
    }

    @Test
    void exitRequestedDisparaPushConKindCorrecto() {
        UUID g = UUID.randomUUID();
        when(contexts.find(invitationId)).thenReturn(Optional.of(context()));
        when(openGuards.findGuardUserIds(neighborhoodId, gateId)).thenReturn(List.of(g));

        useCase.onResidentDecisionMade(new ResidentDecisionMadeEvent(
                neighborhoodId, invitationId, ResidentDecisionMadeEvent.Kind.EXIT_REQUESTED));

        ArgumentCaptor<PushSender.GuardResidentDecision> payload =
                ArgumentCaptor.forClass(PushSender.GuardResidentDecision.class);
        verify(push).notifyGuardOfResidentDecision(eq(g), payload.capture());
        assertThat(payload.getValue().kind()).isEqualTo(PushSender.GuardResidentDecision.Kind.EXIT_REQUESTED);
    }

    @Test
    void skipSilenciosoSiNoHayEstadiaAbierta() {
        when(contexts.find(invitationId)).thenReturn(Optional.empty());

        useCase.onResidentDecisionMade(new ResidentDecisionMadeEvent(
                neighborhoodId, invitationId, ResidentDecisionMadeEvent.Kind.EXTENDED));

        verifyNoInteractions(openGuards);
        verifyNoInteractions(push);
    }

    @Test
    void skipSilenciosoSiNoHayTurnosAbiertosEnElGate() {
        when(contexts.find(invitationId)).thenReturn(Optional.of(context()));
        when(openGuards.findGuardUserIds(neighborhoodId, gateId)).thenReturn(List.of());

        useCase.onResidentDecisionMade(new ResidentDecisionMadeEvent(
                neighborhoodId, invitationId, ResidentDecisionMadeEvent.Kind.EXIT_REQUESTED));

        verify(push, never()).notifyGuardOfResidentDecision(any(), any());
    }

    @Test
    void excepcionDelSenderNoSePropaga() {
        UUID g = UUID.randomUUID();
        when(contexts.find(invitationId)).thenReturn(Optional.of(context()));
        when(openGuards.findGuardUserIds(neighborhoodId, gateId)).thenReturn(List.of(g));
        org.mockito.Mockito.doThrow(new RuntimeException("expo down"))
                .when(push).notifyGuardOfResidentDecision(any(), any());

        // No debe propagar: la HTTP response al residente ya se devolvio y el
        // handler corre en pushExecutor. Un fallo del sender solo se loguea.
        useCase.onResidentDecisionMade(new ResidentDecisionMadeEvent(
                neighborhoodId, invitationId, ResidentDecisionMadeEvent.Kind.EXTENDED));
    }
}
