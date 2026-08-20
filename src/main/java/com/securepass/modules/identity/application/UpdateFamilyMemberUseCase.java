package com.securepass.modules.identity.application;

import com.securepass.modules.identity.domain.FamilyMember;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.exception.ResourceNotFoundException;
import com.securepass.shared.exception.StateConflictException;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class UpdateFamilyMemberUseCase {

    private final FamilyMemberRepository family;
    private final UserRepository users;
    private final ActiveInvitationRevoker invitations;

    public UpdateFamilyMemberUseCase(
            FamilyMemberRepository family,
            UserRepository users,
            ActiveInvitationRevoker invitations) {
        this.family = family;
        this.users = users;
        this.invitations = invitations;
    }

    @Transactional
    public FamilyMemberView update(
            CurrentUser actor, UUID lotId, UUID memberUserId, Boolean canInvite, Boolean active) {
        if (actor == null) throw new IllegalArgumentException("actor requerido");
        if (lotId == null) throw new IllegalArgumentException("lotId requerido");
        if (memberUserId == null) throw new IllegalArgumentException("memberUserId requerido");
        if (canInvite == null && active == null) {
            throw new IllegalArgumentException("canInvite o active requerido");
        }

        requireOwner(actor, lotId);

        FamilyMember member = family.find(memberUserId, lotId)
                .orElseThrow(() -> new ResourceNotFoundException("miembro", memberUserId));
        if (member.isOwner()) {
            throw new StateConflictException("el titular no se modifica desde aca");
        }

        boolean newCanInvite = canInvite != null ? canInvite : member.canInvite();
        boolean newActive = active != null ? active : member.active();

        if (!newActive && member.active()) {
            // US-07: al dar de baja un miembro, sus invitaciones activas
            // para el lote se revocan en cascada.
            invitations.revokeActiveByLotAndUser(lotId, memberUserId, actor.userId());
        }

        family.update(memberUserId, lotId, newCanInvite, newActive);

        User user = users.findById(memberUserId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", memberUserId));
        return new FamilyMemberView(
                memberUserId, user.fullName(), user.email(), user.phone(),
                member.relation(), member.isOwner(), newCanInvite, newActive);
    }

    private void requireOwner(CurrentUser actor, UUID lotId) {
        FamilyMember membership = family.find(actor.userId(), lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!membership.active() || !membership.isOwner()) {
            throw new AccessDeniedException("solo el titular administra la unidad familiar");
        }
    }
}
