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

/**
 * WBS-112 / US-59: baja de familiar y toggle de {@code canInvite}.
 *
 * La baja se hace por PATCH {@code active=false} (no {@code DELETE}) porque
 * el permiso se revoca, no el usuario — el user sigue vivo por si es residente
 * en otro lote. La cascada revoca todas las invitaciones ACTIVE que el
 * familiar creo en ese lote, incluido su resident_pass; ver
 * {@link com.securepass.modules.invitation.infrastructure.persistence.ActiveInvitationJdbcRevoker}
 * para el racional del filtro por {@code created_by}.
 *
 * <p><b>Presencia in situ:</b> revocar el QR impide entrar, no expulsa a quien
 * ya entro. Las {@code stays} abiertas de invitados del familiar no se cierran
 * aca; se manejan por el flujo de {@code stays} (cierre manual US-39,
 * alerta de permanencia excedida BR-08). Decision explicita, no omision:
 * expulsar es un acto fisico/social del guardia, no un flip de DB.
 */
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
    public Result update(
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

        Outcome outcome;
        int revokedCount = 0;
        boolean bajaRequested = Boolean.FALSE.equals(active);
        if (bajaRequested && !member.active()) {
            // Idempotente: ya estaba dado de baja. No re-revoca (evita
            // volver a tocar revoked_at de invitaciones viejas y de paso
            // ensuciar el sync incremental de /access/revoked).
            outcome = Outcome.ALREADY_INACTIVE;
        } else if (!newActive && member.active()) {
            revokedCount = invitations.revokeActiveByLotAndUser(lotId, memberUserId, actor.userId());
            outcome = Outcome.REVOKED_MEMBER;
        } else {
            // canInvite toggle o reactivacion; no dispara cascada.
            outcome = Outcome.TOGGLED_CAN_INVITE;
        }

        family.update(memberUserId, lotId, newCanInvite, newActive);

        User user = users.findById(memberUserId)
                .orElseThrow(() -> new ResourceNotFoundException("usuario", memberUserId));
        FamilyMemberView view = new FamilyMemberView(
                memberUserId, user.fullName(), user.email(), user.phone(),
                member.relation(), member.isOwner(), newCanInvite, newActive);
        return new Result(view, outcome, revokedCount);
    }

    private void requireOwner(CurrentUser actor, UUID lotId) {
        FamilyMember membership = family.find(actor.userId(), lotId)
                .orElseThrow(() -> new ResourceNotFoundException("lote", lotId));
        if (!membership.active() || !membership.isOwner()) {
            throw new AccessDeniedException("solo el titular administra la unidad familiar");
        }
    }

    /**
     * WBS-112: outcome del PATCH sobre un miembro.
     *   REVOKED_MEMBER      -> se dio de baja al miembro; N pases revocados.
     *   ALREADY_INACTIVE    -> pedido idempotente, ya estaba de baja.
     *   TOGGLED_CAN_INVITE  -> cambio can_invite (o reactivacion sin baja);
     *                          revokedInvitationsCount siempre 0.
     */
    public enum Outcome {
        REVOKED_MEMBER,
        ALREADY_INACTIVE,
        TOGGLED_CAN_INVITE
    }

    public record Result(FamilyMemberView view, Outcome outcome, int revokedInvitationsCount) {}
}
