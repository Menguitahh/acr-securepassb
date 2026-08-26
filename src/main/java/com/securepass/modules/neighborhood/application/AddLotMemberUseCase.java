package com.securepass.modules.neighborhood.application;

import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotMember;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AddLotMemberUseCase {

    private final LotRepository lots;
    private final LotMemberRepository members;
    private final MembershipGranter memberships;

    public AddLotMemberUseCase(
            LotRepository lots,
            LotMemberRepository members,
            MembershipGranter memberships) {
        this.lots = lots;
        this.members = members;
        this.memberships = memberships;
    }

    /**
     * WBS-107: consulta si el user ya es miembro del lote antes de llamar
     * a add(). Permite al caller decidir entre "sumar permiso" y
     * "idempotente 200 sin cambios".
     */
    public boolean isMemberOfLot(UUID lotId, UUID userId) {
        return members.find(lotId, userId).isPresent();
    }

    public LotMember add(UUID neighborhoodId, UUID lotId, Command cmd) {
        if (neighborhoodId == null) throw new IllegalArgumentException("neighborhoodId requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (cmd == null) throw new IllegalArgumentException("cmd requerido");

        Lot lot = lots.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!lot.neighborhoodId().equals(neighborhoodId)) {
            throw new ResourceNotFoundException("lote", lotId);
        }

        members.find(lotId, cmd.userId()).ifPresent(existing -> {
            throw new StateConflictException("usuario ya vinculado al lote");
        });
        if (cmd.isOwner()) {
            members.findOwner(lotId).ifPresent(existing -> {
                throw new StateConflictException("el lote ya tiene titular");
            });
        }

        LotMember member = new LotMember(
                UUID.randomUUID(), lotId, cmd.userId(), cmd.relation(),
                cmd.isOwner(), cmd.isOwner() || cmd.canInvite(), true, Instant.now());
        members.save(member);

        Role role = member.isOwner() ? Role.RESIDENT_OWNER : Role.RESIDENT_MEMBER;
        memberships.grant(cmd.userId(), neighborhoodId, role);
        return member;
    }

    public record Command(UUID userId, String relation, boolean isOwner, boolean canInvite) {}
}
