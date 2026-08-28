package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Membership;
import com.securepass.modules.identity.domain.Role;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FamilyMemberRepository {

    Optional<FamilyMember> find(UUID userId, UUID lotId);

    List<FamilyMember> findByLot(UUID lotId);

    void save(FamilyMember member);

    void update(UUID userId, UUID lotId, boolean canInvite, boolean active);

    /**
     * WBS-110: reactiva un permiso MEMBER inactivo del lote, actualizando
     * tambien {@code can_invite} y {@code relation} porque el titular pudo
     * cambiar de opinion sobre ambos al re-agregar. {@code update()} no toca
     * {@code relation}, por eso este metodo aparte.
     */
    void reactivate(UUID userId, UUID lotId, boolean canInvite, String relation);

    UUID neighborhoodIdOfLot(UUID lotId);

    Optional<Membership> findMembership(UUID userId, UUID neighborhoodId, Role role);

    void saveMembership(Membership membership);
}
