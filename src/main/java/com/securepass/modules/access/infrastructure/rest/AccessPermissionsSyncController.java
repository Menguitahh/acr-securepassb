package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.ActiveWorkerPermissionRow;
import com.securepass.modules.access.application.ListActiveWorkerPermissionsUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * WBS-117: sync de permisos WORKER activos del barrio. El scanner (WBS-118)
 * lo consume para cachear en SQLite y evaluar validate offline.
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessPermissionsSyncController {

    private final ListActiveWorkerPermissionsUseCase useCase;
    private final AccessGuard guard;

    public AccessPermissionsSyncController(ListActiveWorkerPermissionsUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    @GetMapping("/permissions/sync")
    @PreAuthorize("hasRole('GUARD')")
    public PermissionsSyncResponse sync(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        CurrentUser actor = guard.require();
        return PermissionsSyncResponse.from(useCase.list(actor, since));
    }
}
