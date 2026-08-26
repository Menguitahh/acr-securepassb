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

    UUID neighborhoodIdOfLot(UUID lotId);

    Optional<Membership> findMembership(UUID userId, UUID neighborhoodId, Role role);

    void saveMembership(Membership membership);
}
