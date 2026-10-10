package com.securepass.modules.invitation.infrastructure.rest;

import com.securepass.modules.invitation.application.IssueWorkerTokenUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import lombok.AllArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WBS-116 / US-65: el trabajador consulta su QR permanente.
 *
 * <p>Get-or-create: la primera vez genera y persiste el JTI; despues lo
 * rehidrata firmandolo con la signing key activa del barrio. Sin permisos
 * WORKER vigentes devuelve 404.
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/me/worker-qr")
public class WorkerQrController {

    private final IssueWorkerTokenUseCase useCase;
    private final AccessGuard accessGuard;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public WorkerQrResponse get() {
        CurrentUser actor = accessGuard.require();
        IssueWorkerTokenUseCase.IssuedWorkerToken issued = useCase.issueForCurrentUser(actor.userId());
        return new WorkerQrResponse(issued.jti(), issued.sp1());
    }
}
