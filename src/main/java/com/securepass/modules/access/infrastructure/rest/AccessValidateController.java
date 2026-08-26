package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.RecordAccessAttemptUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/access")
public class AccessValidateController {

    private final RecordAccessAttemptUseCase useCase;
    private final AccessGuard guard;

    public AccessValidateController(RecordAccessAttemptUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    @PostMapping("/validate")
    @PreAuthorize("hasRole('GUARD')")
    public ValidateAccessResponse validate(@Valid @RequestBody ValidateAccessRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        RecordAccessAttemptUseCase.Result result = useCase.record(
                request.toCommand(user.neighborhoodId(), user.userId()));
        return ValidateAccessResponse.from(result);
    }
}
