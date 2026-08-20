package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.Membership;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AddFamilyMemberUseCase {

    private final FamilyMemberRepository family;
    private final UserRepository users;

    public AddFamilyMemberUseCase(FamilyMemberRepository family, UserRepository users) {
        this.family = family;
        this.users = users;
    }

    @Transactional
    public FamilyMemberView add(CurrentUser actor, UUID lotId, String identifier, boolean canInvite, String relation) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("identifier requerido");
        }

        requireOwner(actor, lotId);

        User user = users.findByIdentifier(identifier.trim())
                .orElseThrow(() -> new ResourceNotFoundException("usuario", identifier));
        if (!user.isActive()) {
            throw new StateConflictException("el usuario no esta activo");
        }
        if (family.find(user.id(), lotId).isPresent()) {
            throw new StateConflictException("el usuario ya es miembro del lote");
        }

        family.save(new FamilyMember(user.id(), lotId, relation, false, canInvite, true));
        ensureResidentMembership(user.id(), lotId);

        return new FamilyMemberView(
                user.id(), user.fullName(), user.email(), user.phone(),
                relation, false, canInvite, true);
    }

    private void requireOwner(CurrentUser actor, UUID lotId) {
        FamilyMember membership = family.find(actor.userId(), lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!membership.active() || !membership.isOwner()) {
            throw new AccessDeniedException("solo el titular administra la unidad familiar");
        }
    }

    private void ensureResidentMembership(UUID userId, UUID lotId) {
        UUID neighborhoodId = family.neighborhoodIdOfLot(lotId);
        if (family.findMembership(userId, neighborhoodId, Role.RESIDENT_MEMBER).isPresent()) {
            return;
        }
        family.saveMembership(new Membership(
                UUID.randomUUID(), userId, neighborhoodId, Role.RESIDENT_MEMBER, true));
    }
}
