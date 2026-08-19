package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.CreateInvitationUseCase;
import com.securepass.modules.invitation.application.GetInvitationUseCase;
import com.securepass.modules.invitation.application.IssuedInvitation;
import com.securepass.modules.invitation.application.ListInvitationsUseCase;
import com.securepass.modules.invitation.application.QrTokenIssuer;
import com.securepass.modules.invitation.application.RevokeInvitationUseCase;
import com.securepass.modules.invitation.domain.Invitation;
import com.securepass.modules.invitation.domain.InvitationStatus;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

    private final CreateInvitationUseCase createUseCase;
    private final ListInvitationsUseCase listUseCase;
    private final GetInvitationUseCase getUseCase;
    private final RevokeInvitationUseCase revokeUseCase;
    private final QrTokenIssuer qrTokenIssuer;
    private final AccessGuard accessGuard;
    private final String shareUrlBase;

    public InvitationController(
            CreateInvitationUseCase createUseCase,
            ListInvitationsUseCase listUseCase,
            GetInvitationUseCase getUseCase,
            RevokeInvitationUseCase revokeUseCase,
            QrTokenIssuer qrTokenIssuer,
            AccessGuard accessGuard,
            @Value("${securepass.share-url-base:https://securepass.app/i}") String shareUrlBase) {
        this.createUseCase = createUseCase;
        this.listUseCase = listUseCase;
        this.getUseCase = getUseCase;
        this.revokeUseCase = revokeUseCase;
        this.qrTokenIssuer = qrTokenIssuer;
        this.accessGuard = accessGuard;
        this.shareUrlBase = shareUrlBase;
    }

    @PostMapping
    public ResponseEntity<InvitationResponse> create(@Valid @RequestBody CreateInvitationRequest request) {
        CurrentUser actor = accessGuard.require();
        IssuedInvitation issued = createUseCase.create(actor.userId(), actor.neighborhoodId(), request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/invitations/" + issued.invitation().id()))
                .body(InvitationResponse.from(issued));
    }

    @GetMapping
    public PagedInvitationsResponse list(
            @RequestParam(required = false) UUID lotId,
            @RequestParam(required = false) InvitationStatus status,
            @RequestParam(defaultValue = "0") int page) {
        CurrentUser actor = accessGuard.require();
        return PagedInvitationsResponse.from(listUseCase.list(actor, lotId, status, page));
    }

    @GetMapping("/{id}")
    public InvitationResponse detail(@PathVariable UUID id) {
        CurrentUser actor = accessGuard.require();
        Invitation invitation = getUseCase.get(actor, id);
        String qrToken = qrTokenIssuer.issue(new QrTokenIssuer.IssueCommand(
                invitation.neighborhoodId(), invitation.lotId(), invitation.id(), invitation.jti(),
                invitation.type(), invitation.validFrom(), invitation.validUntil(), invitation.maxUses(),
                invitation.weekdays(), invitation.dailyFrom(), invitation.dailyUntil()));
        return InvitationResponse.from(invitation, qrToken, shareUrlFor(invitation.id()));
    }

    @PostMapping("/{id}/revoke")
    public InvitationResponse revoke(@PathVariable UUID id) {
        CurrentUser actor = accessGuard.require();
        Invitation revoked = revokeUseCase.revoke(actor, id);
        return InvitationResponse.withoutQr(revoked);
    }

    private String shareUrlFor(UUID id) {
        return shareUrlBase + "/" + id.toString().replace("-", "").substring(0, 8);
    }
}
