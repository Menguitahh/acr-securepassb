package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.DeleteMinorUseCase;
import com.securepass.modules.identity.application.GraduateMinorUseCase;
import com.securepass.modules.identity.application.ListMinorsUseCase;
import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.identity.application.RegisterMinorUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.Minor;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
@RequestMapping("/api/v1/family/lots/{lotId}/minors")
public class FamilyMinorsController {

    private final RegisterMinorUseCase register;
    private final ListMinorsUseCase list;
    private final DeleteMinorUseCase delete;
    private final GraduateMinorUseCase graduate;
    private final UserRepository users;
    private final NeighborhoodNameQuery neighborhoods;
    private final AccessGuard guard;

    @PostMapping
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public ResponseEntity<MinorResponse> register(
            @PathVariable UUID lotId,
            @Valid @RequestBody RegisterMinorRequest request) {
        CurrentUser actor = guard.require();
        Minor minor = register.register(actor, lotId, request.toCommand());
        return ResponseEntity
                .created(URI.create("/api/v1/family/lots/" + lotId + "/minors/" + minor.id()))
                .body(MinorResponse.from(minor));
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or @lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).MEMBER)")
    public List<MinorResponse> listMinors(@PathVariable UUID lotId) {
        CurrentUser actor = guard.require();
        return list.list(actor, lotId).stream().map(MinorResponse::from).toList();
    }

    @DeleteMapping("/{minorId}")
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public ResponseEntity<Void> deleteMinor(
            @PathVariable UUID lotId,
            @PathVariable UUID minorId) {
        CurrentUser actor = guard.require();
        delete.delete(actor, lotId, minorId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{minorId}/graduate")
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public ResponseEntity<GraduateMinorResponse> graduateMinor(
            @PathVariable UUID lotId,
            @PathVariable UUID minorId,
            @Valid @RequestBody GraduateMinorRequest request) {
        CurrentUser actor = guard.require();
        GraduateMinorUseCase.Result result = graduate.graduate(
                actor, lotId, minorId, request.toCommand(),
                actorName(actor),
                neighborhoods.findName(actor.neighborhoodId()));
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(GraduateMinorResponse.from(result));
    }

    private String actorName(CurrentUser actor) {
        return users.findById(actor.userId()).map(User::fullName).orElse("Titular");
    }
}
