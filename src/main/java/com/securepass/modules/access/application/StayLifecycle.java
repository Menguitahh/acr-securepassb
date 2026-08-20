package com.securepass.modules.access.application;

import com.securepass.modules.access.domain.AccessLog;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.Stay;
import com.securepass.modules.invitation.domain.Invitation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * BR-14: cada ingreso ALLOWED abre una `stay`; cada egreso ALLOWED cierra la
 * `stay` abierta de esa invitacion. Pensado como hook llamado desde los flujos
 * que persisten `access_logs` (validate/consume, enroll, sync). El open y el
 * close son idempotentes: si ya hay stay abierta no crea otra (indice unico
 * parcial `uq_open_stay_per_invitation`); si no hay abierta al egresar no
 * inventa una (BR-07).
 */
@Service
public class StayLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(StayLifecycle.class);

    private final StayRepository stays;

    public StayLifecycle(StayRepository stays) {
        this.stays = stays;
    }

    public Optional<Stay> onAllowed(AccessLog log, Invitation invitation) {
        if (log == null || !log.allowed()) return Optional.empty();
        if (invitation == null) return Optional.empty();
        if (invitation.residentPass()) return Optional.empty();

        if (log.direction() == Direction.IN) return Optional.of(openStay(log, invitation));
        if (log.direction() == Direction.OUT) return closeStay(log, invitation);
        return Optional.empty();
    }

    private Stay openStay(AccessLog log, Invitation inv) {
        Stay stay = new Stay(
                UUID.randomUUID(), log.neighborhoodId(), inv.id(), log.lotId(),
                inv.guestName(), inv.guestDocument(), log.companions(),
                log.id(), null, log.capturedAt(), null, inv.validUntil(),
                false, null);
        return stays.openIfNotOpen(stay);
    }

    private Optional<Stay> closeStay(AccessLog log, Invitation inv) {
        Optional<Stay> open = stays.findOpenByInvitationId(inv.id());
        if (open.isEmpty()) {
            // BR-07: un egreso sin ingreso previo no bloquea; el log ya quedo
            // registrado como MANUAL/GUEST. Aca simplemente no hay stay que cerrar.
            LOG.info("stay.close.skipped invitation={} sin stay abierta", inv.id());
            return Optional.empty();
        }
        Stay closed = open.get().close(log.id(), log.capturedAt(), false, log.guardId());
        stays.close(closed);
        return Optional.of(closed);
    }
}
