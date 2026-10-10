package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.AddFamilyMemberUseCase;
import com.securepass.modules.identity.application.ListFamilyMembersUseCase;
import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.identity.application.UpdateFamilyMemberUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/family/lots/{lotId}/members")
public class FamilyController {

    private final ListFamilyMembersUseCase listUseCase;
    private final AddFamilyMemberUseCase addUseCase;
    private final UpdateFamilyMemberUseCase updateUseCase;
    private final UserRepository users;
    private final NeighborhoodNameQuery neighborhoods;
    private final AccessGuard accessGuard;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or @lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).MEMBER)")
    public List<FamilyMemberResponse> list(@PathVariable UUID lotId) {
        CurrentUser actor = accessGuard.require();
        return listUseCase.list(actor, lotId).stream().map(FamilyMemberResponse::from).toList();
    }

    // WBS-110: gate del titular. Sin este @PreAuthorize, cualquier RESIDENT_MEMBER
    // podria dar de alta familiares en un lote donde solo tiene permiso MEMBER.
    @PostMapping
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public ResponseEntity<AddFamilyMemberResponse> add(
            @PathVariable UUID lotId,
            @Valid @RequestBody AddFamilyMemberRequest request) {
        CurrentUser actor = accessGuard.require();
        AddFamilyMemberUseCase.Result result = addUseCase.add(
                actor, lotId, request.toCommand(),
                actorName(actor),
                neighborhoods.findName(actor.neighborhoodId()));
        AddFamilyMemberResponse body = AddFamilyMemberResponse.from(result);
        if (result.changed()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/family/lots/" + lotId + "/members/" + result.userId()))
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public UpdateFamilyMemberResponse update(
            @PathVariable UUID lotId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateFamilyMemberRequest request) {
        CurrentUser actor = accessGuard.require();
        UpdateFamilyMemberUseCase.Result result = updateUseCase.update(
                actor, lotId, userId, request.canInvite(), request.active());
        return UpdateFamilyMemberResponse.from(result);
    }

    private String actorName(CurrentUser actor) {
        return users.findById(actor.userId()).map(User::fullName).orElse("Titular");
    }
}
