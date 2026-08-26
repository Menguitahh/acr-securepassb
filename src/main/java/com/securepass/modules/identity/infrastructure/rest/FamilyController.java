package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.AddFamilyMemberUseCase;
import com.securepass.modules.identity.application.FamilyMemberView;
import com.securepass.modules.identity.application.ListFamilyMembersUseCase;
import com.securepass.modules.identity.application.UpdateFamilyMemberUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/v1/family/lots/{lotId}/members")
public class FamilyController {

    private final ListFamilyMembersUseCase listUseCase;
    private final AddFamilyMemberUseCase addUseCase;
    private final UpdateFamilyMemberUseCase updateUseCase;
    private final AccessGuard accessGuard;

    public FamilyController(
            ListFamilyMembersUseCase listUseCase,
            AddFamilyMemberUseCase addUseCase,
            UpdateFamilyMemberUseCase updateUseCase,
            AccessGuard accessGuard) {
        this.listUseCase = listUseCase;
        this.addUseCase = addUseCase;
        this.updateUseCase = updateUseCase;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','RESIDENT_OWNER','RESIDENT_MEMBER')")
    public List<FamilyMemberResponse> list(@PathVariable UUID lotId) {
        CurrentUser actor = accessGuard.require();
        return listUseCase.list(actor, lotId).stream().map(FamilyMemberResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('RESIDENT_OWNER')")
    public ResponseEntity<FamilyMemberResponse> add(
            @PathVariable UUID lotId,
            @Valid @RequestBody AddFamilyMemberRequest request) {
        CurrentUser actor = accessGuard.require();
        FamilyMemberView member = addUseCase.add(
                actor, lotId, request.identifier(), request.canInvite(), request.relation());
        return ResponseEntity
                .created(URI.create("/api/v1/family/lots/" + lotId + "/members/" + member.userId()))
                .body(FamilyMemberResponse.from(member));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasRole('RESIDENT_OWNER')")
    public FamilyMemberResponse update(
            @PathVariable UUID lotId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateFamilyMemberRequest request) {
        CurrentUser actor = accessGuard.require();
        FamilyMemberView member = updateUseCase.update(
                actor, lotId, userId, request.canInvite(), request.active());
        return FamilyMemberResponse.from(member);
    }
}
