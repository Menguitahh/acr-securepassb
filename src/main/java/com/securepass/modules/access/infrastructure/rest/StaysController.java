package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.identity.domain.Role;

import com.securepass.modules.access.application.CloseStayManuallyUseCase;
import com.securepass.modules.access.application.ListOpenStaysUseCase;
import com.securepass.modules.access.application.MarkStayWarnedUseCase;
import com.securepass.modules.access.domain.Stay;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
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

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/stays")
public class StaysController {

    private final ListOpenStaysUseCase listUseCase;
    private final CloseStayManuallyUseCase closeUseCase;
    private final MarkStayWarnedUseCase markWarnedUseCase;
    private final AccessGuard guard;

    @GetMapping("/open")
    @PreAuthorize("hasAnyRole('GUARD','ADMIN','RESIDENT_OWNER','RESIDENT_MEMBER')")
    public OpenStaysResponse open() {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        boolean staff = user.hasRole(Role.GUARD) || user.hasRole(Role.ADMIN);
        return OpenStaysResponse.from(
                staff ? listUseCase.list(user.neighborhoodId()) : listUseCase.listForLots(user.neighborhoodId(), user.lots()),
                Instant.now());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('GUARD')")
    public ClosedStayResponse close(@PathVariable UUID id, @Valid @RequestBody CloseStayRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        Stay closed = closeUseCase.close(user.neighborhoodId(), request.gateId(), user.userId(), id);
        return ClosedStayResponse.from(closed);
    }

    @PostMapping("/{id}/mark-warned")
    @PreAuthorize("hasRole('GUARD')")
    public MarkStayWarnedResponse markWarned(@PathVariable UUID id) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return MarkStayWarnedResponse.from(markWarnedUseCase.mark(user.neighborhoodId(), id));
    }
}
