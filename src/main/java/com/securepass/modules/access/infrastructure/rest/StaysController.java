package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ListOpenStaysUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/stays")
public class StaysController {

    private final ListOpenStaysUseCase useCase;
    private final AccessGuard guard;

    public StaysController(ListOpenStaysUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('GUARD','ADMIN')")
    public OpenStaysResponse open() {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return OpenStaysResponse.from(useCase.list(user.neighborhoodId()), Instant.now());
    }
}
