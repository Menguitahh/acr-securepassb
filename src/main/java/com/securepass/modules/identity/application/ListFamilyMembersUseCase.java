package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ListFamilyMembersUseCase {

    private final FamilyMemberRepository family;
    private final UserRepository users;

    public ListFamilyMembersUseCase(FamilyMemberRepository family, UserRepository users) {
        this.family = family;
        this.users = users;
    }

    public List<FamilyMemberView> list(CurrentUser actor, UUID lotId) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");

        if (actor.hasRole(Role.ADMIN)) {
            // Unico guardian cross-barrio para ADMIN: el @PreAuthorize deja pasar
            // hasRole('ADMIN') sin evaluar lot_permissions, y un ADMIN del barrio A
            // no tiene ninguna entrada que lo pare de leer familia del B. No borrar.
            // Fail-closed: si el lote no existe o no se puede resolver, se deniega
            // (evita 500 por lote inventado).
            UUID lotNeighborhood;
            try {
                lotNeighborhood = family.neighborhoodIdOfLot(lotId);
            } catch (RuntimeException e) {
                throw new AccessDeniedException("no se pudo determinar el barrio del lote");
            }
            if (lotNeighborhood == null || !lotNeighborhood.equals(actor.neighborhoodId())) {
                throw new AccessDeniedException("recurso de otro barrio");
            }
        } else {
            FamilyMember membership = family.find(actor.userId(), lotId)
                    .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
            if (!membership.active()) {
                throw new AccessDeniedException("sin acceso a este lote");
            }
        }

        return family.findByLot(lotId).stream()
                .flatMap(member -> users.findById(member.userId())
                        .map(user -> new FamilyMemberView(
                                member.userId(), user.fullName(), user.email(), user.phone(),
                                member.relation(), member.isOwner(), member.canInvite(), member.active(),
                                user.status()))
                        .stream())
                .toList();
    }
}
