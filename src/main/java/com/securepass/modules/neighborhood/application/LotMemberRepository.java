package com.securepass.modules.neighborhood.application;

import com.securepass.modules.neighborhood.domain.LotMember;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LotMemberRepository {

    Optional<LotMember> findOwner(UUID lotId);

    Optional<LotMember> find(UUID lotId, UUID userId);

    List<LotMember> listByLot(UUID lotId);

    void save(LotMember member);
}
