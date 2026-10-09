package com.securepass.modules.notification.application;

import com.securepass.modules.invitation.application.ResidentDecisionMadeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-72 / US-42: al terminar OK la tx del residente (extender o pedir
 * retiro), notificar a la garita donde ingreso la visita. Push targeted:
 * resuelve el gate del `entry_log_id` de la estadia y despacha push a
 * cada guardia con turno abierto en ese gate.
 *
 * AFTER_COMMIT: un rollback del extend/request-exit NO dispara push
 * fantasma. `@Async("pushExecutor")` (virtual threads): la HTTP response
 * al residente no espera el push.
 *
 * Skips silenciosos (no rompe la operacion, deja rastro en log):
 *  - estadia cerrada entre commit y listener: no hay contexto.
 *  - ningun turno abierto en el gate: mandar push a un guardia en su casa
 *    es peor que no mandar nada; la garita se entera al proximo GET
 *    `/stays/open`.
 *  - guardia sin devices registrados: lo maneja `PushSender` internamente
 *    (loguea `push.skip user=...`).
 */
@Service
public class NotifyGuardsOfResidentDecisionUseCase {

    private static final Logger LOG = LoggerFactory.getLogger(NotifyGuardsOfResidentDecisionUseCase.class);

    private final ResidentDecisionContextQuery contexts;
    private final OpenGuardsAtGateQuery openGuards;
    private final PushSender push;

    public NotifyGuardsOfResidentDecisionUseCase(
            ResidentDecisionContextQuery contexts,
            OpenGuardsAtGateQuery openGuards,
            PushSender push) {
        this.contexts = contexts;
        this.openGuards = openGuards;
        this.push = push;
    }

    @Async("pushExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResidentDecisionMade(ResidentDecisionMadeEvent event) {
        try {
            handle(event);
        } catch (RuntimeException ex) {
            LOG.warn("push.resident-decision.failed invitation={} kind={} error={}",
                    event.invitationId(), event.kind(), ex.getMessage());
        }
    }

    private void handle(ResidentDecisionMadeEvent event) {
        Optional<ResidentDecisionContextQuery.Context> ctx = contexts.find(event.invitationId());
        if (ctx.isEmpty()) {
            LOG.info("push.resident-decision.skip.no-open-stay invitation={}", event.invitationId());
            return;
        }
        ResidentDecisionContextQuery.Context c = ctx.get();

        List<UUID> guards = openGuards.findGuardUserIds(event.neighborhoodId(), c.gateId());
        if (guards.isEmpty()) {
            LOG.info("push.resident-decision.skip.no-open-shift gate={} invitation={}",
                    c.gateId(), event.invitationId());
            return;
        }

        PushSender.GuardResidentDecision payload = new PushSender.GuardResidentDecision(
                c.stayId(), event.invitationId(),
                map(event.kind()),
                c.subjectName(), c.residentName(),
                c.expectedUntil());

        for (UUID guardId : guards) {
            push.notifyGuardOfResidentDecision(guardId, payload);
        }
    }

    private static PushSender.GuardResidentDecision.Kind map(ResidentDecisionMadeEvent.Kind k) {
        return switch (k) {
            case EXTENDED -> PushSender.GuardResidentDecision.Kind.EXTENDED;
            case EXIT_REQUESTED -> PushSender.GuardResidentDecision.Kind.EXIT_REQUESTED;
        };
    }
}
