package com.securepass.modules.notification.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * WBS-72: resuelve, para la estadia abierta de una invitacion, el contexto
 * que necesita el push a garita: gate del ingreso, nombre del invitado y
 * del residente, y `expected_until` (para el body de EXTENDED).
 *
 * Devuelve `empty` si no hay estadia abierta (la estadia se cerro entre el
 * commit del extend/request-exit y la ejecucion del listener). El listener
 * lo trata como skip silencioso.
 */
public interface ResidentDecisionContextQuery {

    Optional<Context> find(UUID invitationId);

    record Context(
            UUID stayId,
            UUID gateId,
            String subjectName,
            String residentName,
            Instant expectedUntil
    ) {}
}
