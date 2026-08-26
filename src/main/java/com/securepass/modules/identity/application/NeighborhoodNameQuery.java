package com.securepass.modules.identity.application;

import java.util.UUID;

/**
 * Nombre del barrio, para el contexto del email de activacion. Duplicado del
 * puerto homonimo de invitation para no cruzar modulos; ambos leen la misma
 * tabla y el mismo adapter en identity los sirve.
 */
public interface NeighborhoodNameQuery {
    String findName(UUID neighborhoodId);
}
