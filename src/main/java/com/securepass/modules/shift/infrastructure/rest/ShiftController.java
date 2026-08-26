package com.securepass.modules.shift.infrastructure.rest;

import com.securepass.modules.shift.application.CloseShiftUseCase;
import com.securepass.modules.shift.application.GetCurrentShiftUseCase;
import com.securepass.modules.shift.application.ListShiftsUseCase;
import com.securepass.modules.shift.application.OpenShiftUseCase;
import com.securepass.modules.shift.domain.Shift;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shifts")
public class ShiftController {

    private final OpenShiftUseCase openUseCase;
    private final CloseShiftUseCase closeUseCase;
    private final GetCurrentShiftUseCase currentUseCase;
    private final ListShiftsUseCase listUseCase;
    private final AccessGuard guard;

    public ShiftController(
            OpenShiftUseCase openUseCase,
            CloseShiftUseCase closeUseCase,
            GetCurrentShiftUseCase currentUseCase,
            ListShiftsUseCase listUseCase,
            AccessGuard guard) {
        this.openUseCase = openUseCase;
        this.closeUseCase = closeUseCase;
        this.currentUseCase = currentUseCase;
        this.listUseCase = listUseCase;
        this.guard = guard;
    }

    @PostMapping("/open")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ShiftResponse> open(@Valid @RequestBody OpenShiftRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        Shift opened = openUseCase.open(user.neighborhoodId(), request.gateId(), user.userId(), request.handoverNotes());
        return ResponseEntity.created(URI.create("/api/v1/shifts/" + opened.id()))
                .body(ShiftResponse.from(opened));
    }

    @PostMapping("/close")
    @PreAuthorize("hasRole('GUARD')")
    public ShiftResponse close(@Valid @RequestBody CloseShiftRequest request) {
        CurrentUser user = guard.require();
        return ShiftResponse.from(closeUseCase.close(user.userId(), request.handoverNotes()));
    }

    @GetMapping("/current")
    @PreAuthorize("hasRole('GUARD')")
    public ShiftResponse current() {
        CurrentUser user = guard.require();
        return currentUseCase.get(user.userId())
                .map(ShiftResponse::from)
                .orElse(null);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<ShiftResponse> list(
            @RequestParam(required = false) UUID gateId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return listUseCase.list(user.neighborhoodId(), gateId, from, to).stream()
                .map(ShiftResponse::from).toList();
    }
}
