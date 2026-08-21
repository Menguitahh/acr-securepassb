package com.securepass.modules.invitation.application;

import com.securepass.modules.access.application.StayRepository;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.shared.exception.BusinessRuleViolationException;
import com.securepass.shared.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * US-42/43: extender la ventana de una invitacion segun politica del
 * barrio.
 *
 * BR-16: suma `extension_minutes` a `valid_until` e incrementa
 *  `extension_count`; rechaza si `extension_count >= max_extensions`;
 *  actualiza `expected_until` de la stay abierta.
 * BR-17: solo el creador o el titular del lote pueden extender.
 */
@Service
public class ExtendInvitationUseCase {

    private final InvitationRepository invitations;
    private final NeighborhoodExtensionPolicyQuery policies;
    private final FamilyMemberQuery family;
    private final StayRepository stays;

    public ExtendInvitationUseCase(
            InvitationRepository invitations,
            NeighborhoodExtensionPolicyQuery policies,
            FamilyMemberQuery family,
            StayRepository stays) {
        this.invitations = invitations;
        this.policies = policies;
        this.family = family;
        this.stays = stays;
    }

    @Transactional
    public Result extend(UUID actorNeighborhoodId, UUID actorUserId, UUID invitationId) {
        if (actorNeighborhoodId == null) throw new IllegalArgumentException("actorNeighborhoodId requerido");
        if (actorUserId == null) throw new IllegalArgumentException("actorUserId requerido");
        if (invitationId == null) throw new IllegalArgumentException("invitationId requerido");

        Invitation inv = invitations.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("invitation", invitationId));
        // Aislamiento por barrio (spec 5.5).
        if (!inv.neighborhoodId().equals(actorNeighborhoodId)) {
            throw new ResourceNotFoundException("invitation", invitationId);
        }
        assertActorCanExtend(actorUserId, inv);

        if (inv.status() != InvitationStatus.ACTIVE) {
            throw new BusinessRuleViolationException("BR-16",
                    "solo ACTIVE se puede extender, estado: " + inv.status());
        }

        NeighborhoodExtensionPolicy policy = policies.find(inv.neighborhoodId())
                .orElseThrow(() -> new IllegalStateException(
                        "sin politica de extension para barrio " + inv.neighborhoodId()));

        int current = invitations.findExtensionCount(invitationId);
        if (current >= policy.maxExtensions()) {
            throw new BusinessRuleViolationException("BR-16",
                    "extension_count " + current + " alcanzo el tope " + policy.maxExtensions());
        }

        java.time.Instant newValidUntil = inv.validUntil().plus(Duration.ofMinutes(policy.extensionMinutes()));
        Invitation extended = invitations.applyExtension(invitationId, newValidUntil, current + 1);

        // BR-16: actualizar expected_until de la stay abierta, si hay.
        stays.updateExpectedUntilByInvitation(invitationId, newValidUntil);

        return new Result(extended, current + 1, policy.maxExtensions());
    }

    private void assertActorCanExtend(UUID actorUserId, Invitation inv) {
        if (actorUserId.equals(inv.createdBy())) return; // creador (BR-17)
        boolean owner = family.find(actorUserId, inv.lotId())
                .map(m -> m.active() && m.isOwner())
                .orElse(false);
        if (!owner) {
            throw new AccessDeniedException("BR-17: solo el creador o el titular del lote extienden");
        }
    }

    public record Result(Invitation invitation, int extensionCount, int maxExtensions) {}
}
