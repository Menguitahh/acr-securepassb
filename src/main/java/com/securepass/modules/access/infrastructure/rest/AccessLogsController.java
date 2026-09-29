package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ListAccessLogsUseCase;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/access-logs")
public class AccessLogsController {

    private final ListAccessLogsUseCase useCase;
    private final AccessGuard guard;

    public AccessLogsController(ListAccessLogsUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    // reason como enum: un valor fuera de DenialReason es 400 (TypeMismatch) y
    // no una lista vacia que parezca "sin denegados" (#204).
    @GetMapping
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "reason fuera del enum, o reason junto con result=ALLOWED: "
            + "no existe un acceso permitido con motivo de denegacion",
            content = @Content(mediaType = "application/problem+json"))
    @PreAuthorize("hasAnyRole('ADMIN','RESIDENT_OWNER','RESIDENT_MEMBER')")
    public AccessLogsResponse list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID lotId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) DenialReason reason,
            @RequestParam(defaultValue = "0") int page) {
        CurrentUser actor = guard.require();
        Boolean allowedOnly = null;
        if (result != null) {
            if ("ALLOWED".equalsIgnoreCase(result)) allowedOnly = Boolean.TRUE;
            else if ("DENIED".equalsIgnoreCase(result)) allowedOnly = Boolean.FALSE;
            else throw new IllegalArgumentException("result debe ser ALLOWED o DENIED");
        }
        if (reason != null && Boolean.TRUE.equals(allowedOnly)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason solo aplica a accesos denegados");
        }
        return AccessLogsResponse.from(useCase.list(actor, from, to, lotId, allowedOnly, reason, page));
    }
}
