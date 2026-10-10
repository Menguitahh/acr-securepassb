package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.IssueResidentPassesUseCase;
import com.securepass.modules.invitation.application.ResidentPass;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/me")
public class MePassesController {

    private final IssueResidentPassesUseCase useCase;
    private final AccessGuard guard;

    @GetMapping("/passes")
    @PreAuthorize("hasAnyRole('RESIDENT_OWNER','RESIDENT_MEMBER')")
    public PassesResponse passes() {
        CurrentUser actor = guard.require();
        List<ResidentPass> passes = useCase.issue(actor.userId(), actor.neighborhoodId());
        return new PassesResponse(passes.stream().map(PassResponse::from).toList());
    }

    public record PassesResponse(List<PassResponse> passes) {}

    public record PassResponse(
            java.util.UUID lotId,
            String lotCode,
            String neighborhoodName,
            String qrToken,
            String kid
    ) {
        public static PassResponse from(ResidentPass p) {
            return new PassResponse(p.lotId(), p.lotCode(), p.neighborhoodName(), p.qrToken(), p.kid());
        }
    }
}
