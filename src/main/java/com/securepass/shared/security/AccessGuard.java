package com.securepass.shared.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AccessGuard {

    public CurrentUser require() {
        CurrentUser user = tryCurrent();
        if (user == null) throw new AccessDeniedException("no autenticado");
        return user;
    }

    /**
     * Devuelve el {@link CurrentUser} del contexto o {@code null} si no hay
     * autenticacion aplicable (contexto vacio, principal null o de otro tipo).
     * Fail-closed para callers que decidan que hacer (SpEL evaluators).
     */
    public CurrentUser tryCurrent() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser user)) return null;
        return user;
    }

    public void assertSameNeighborhood(UUID resourceNeighborhoodId) {
        if (resourceNeighborhoodId == null) throw new AccessDeniedException("recurso sin barrio");
        CurrentUser user = require();
        if (user.neighborhoodId() == null || !user.neighborhoodId().equals(resourceNeighborhoodId)) {
            throw new AccessDeniedException("recurso de otro barrio");
        }
    }

    public void assertOwnsLot(UUID lotId) {
        if (lotId == null) throw new AccessDeniedException("recurso sin lote");
        CurrentUser user = require();
        if (!user.lots().contains(lotId)) throw new AccessDeniedException("lote fuera de la unidad familiar");
    }

}
