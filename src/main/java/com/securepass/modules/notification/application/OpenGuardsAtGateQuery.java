package com.securepass.modules.notification.application;

import java.util.List;
import java.util.UUID;

/**
 * WBS-72: guardias con turno abierto (`ended_at IS NULL`) en un gate dado.
 * Devuelve lista vacia si no hay ninguno; el listener del push lo trata
 * como skip silencioso (mandar push a un guardia fuera de turno es peor
 * que no mandar nada: esta en su casa y no puede reaccionar).
 */
public interface OpenGuardsAtGateQuery {

    List<UUID> findGuardUserIds(UUID neighborhoodId, UUID gateId);
}
