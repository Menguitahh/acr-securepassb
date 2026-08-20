package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.EnrollGuestUseCase;
import com.securepass.modules.access.application.EnrollmentResult;
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
public class AccessEnrollController {

    private final EnrollGuestUseCase useCase;
    private final AccessGuard guard;

    public AccessEnrollController(EnrollGuestUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    @PostMapping("/enroll")
    @PreAuthorize("hasRole('GUARD')")
    public EnrollResponse enroll(@Valid @RequestBody EnrollRequest request) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        EnrollmentResult result = useCase.enroll(request.toCommand(user.neighborhoodId(), user.userId()));
        return EnrollResponse.from(result);
    }
}
