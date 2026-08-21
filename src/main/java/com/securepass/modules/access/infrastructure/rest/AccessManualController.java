package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ManualAccessUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/access")
public class AccessManualController {

    private final ManualAccessUseCase useCase;
    private final AccessGuard guard;

    public AccessManualController(ManualAccessUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    @PostMapping("/manual")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ManualAccessResponse> manual(@Valid @RequestBody ManualAccessRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        var log = useCase.record(request.toCommand(user.neighborhoodId(), user.userId()));
        return ResponseEntity.created(URI.create("/api/v1/access-logs/" + log.id()))
                .body(ManualAccessResponse.from(log));
    }
}
