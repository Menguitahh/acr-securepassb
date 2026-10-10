package com.securepass.modules.shift.infrastructure.rest;

import com.securepass.modules.shift.application.CloseShiftUseCase;
import com.securepass.modules.shift.application.GetCurrentShiftUseCase;
import com.securepass.modules.shift.application.ListShiftsUseCase;
import com.securepass.modules.shift.application.OpenShiftUseCase;
import com.securepass.modules.shift.domain.Shift;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
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
import java.util.UUID;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/shifts")
public class ShiftController {

    private final OpenShiftUseCase openUseCase;
    private final CloseShiftUseCase closeUseCase;
    private final GetCurrentShiftUseCase currentUseCase;
    private final ListShiftsUseCase listUseCase;
    private final AccessGuard guard;

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

    // 204 vs 200-nullable: el cliente discrimina por status, no por contenido.
    // Antes devolvia 200 con body null y el front toleraba tres formas (404,
    // 204, 200 vacio) porque el shape no era interpretable; ver securepassf#96.
    @GetMapping("/current")
    @PreAuthorize("hasRole('GUARD')")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Turno abierto del guardia"),
            @ApiResponse(responseCode = "204", description = "El guardia no tiene turno abierto", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<ShiftResponse> current() {
        CurrentUser user = guard.require();
        return currentUseCase.get(user.userId())
                .map(ShiftResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ShiftsResponse list(
            @RequestParam(required = false) UUID gateId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return ShiftsResponse.from(listUseCase.list(user.neighborhoodId(), gateId, from, to, page));
    }
}
