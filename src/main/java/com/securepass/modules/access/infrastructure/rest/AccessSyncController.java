package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.SyncAccessUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/access")
public class AccessSyncController {

    private final SyncAccessUseCase useCase;
    private final AccessGuard guard;

    @PostMapping("/sync")
    @PreAuthorize("hasRole('GUARD')")
    public SyncAccessResponse sync(@Valid @RequestBody SyncAccessRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        SyncAccessUseCase.Report report = useCase.sync(
                user.neighborhoodId(), user.userId(), request.toEvents());
        return SyncAccessResponse.from(report);
    }
}
