package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.CloseStayManuallyUseCase;
import com.securepass.modules.access.application.ListOpenStaysUseCase;
import com.securepass.modules.access.domain.Stay;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stays")
public class StaysController {

    private final ListOpenStaysUseCase listUseCase;
    private final CloseStayManuallyUseCase closeUseCase;
    private final AccessGuard guard;

    public StaysController(
            ListOpenStaysUseCase listUseCase,
            CloseStayManuallyUseCase closeUseCase,
            AccessGuard guard) {
        this.listUseCase = listUseCase;
        this.closeUseCase = closeUseCase;
        this.guard = guard;
    }

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('GUARD','ADMIN')")
    public OpenStaysResponse open() {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return OpenStaysResponse.from(listUseCase.list(user.neighborhoodId()), Instant.now());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('GUARD')")
    public ClosedStayResponse close(@PathVariable UUID id, @Valid @RequestBody CloseStayRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        Stay closed = closeUseCase.close(user.neighborhoodId(), request.gateId(), user.userId(), id);
        return ClosedStayResponse.from(closed);
    }
}
