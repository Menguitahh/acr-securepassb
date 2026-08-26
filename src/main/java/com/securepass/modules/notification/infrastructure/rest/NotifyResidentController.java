package com.securepass.modules.notification.infrastructure.rest;

import com.securepass.modules.notification.application.NotifyResidentOfStayUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/stays")
public class NotifyResidentController {

    private final NotifyResidentOfStayUseCase useCase;
    private final AccessGuard guard;

    public NotifyResidentController(NotifyResidentOfStayUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    @PostMapping("/{id}/notify-resident")
    @PreAuthorize("hasRole('GUARD')")
    public NotifyResidentResponse notify(@PathVariable UUID id) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return NotifyResidentResponse.from(useCase.notify(user.neighborhoodId(), id));
    }
}
