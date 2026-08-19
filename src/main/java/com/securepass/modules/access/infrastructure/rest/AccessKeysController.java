package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.invitation.application.SigningKeyQuery;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/access")
public class AccessKeysController {

    private final SigningKeyQuery signingKeys;
    private final AccessGuard guard;

    public AccessKeysController(SigningKeyQuery signingKeys, AccessGuard guard) {
        this.signingKeys = signingKeys;
        this.guard = guard;
    }

    @GetMapping("/keys")
    @PreAuthorize("hasRole('GUARD')")
    public List<PublicKeyResponse> keys() {
        CurrentUser user = guard.require();
        UUID neighborhoodId = user.neighborhoodId();
        if (neighborhoodId == null) throw new AccessDeniedException("usuario sin barrio en el token");
        return signingKeys.findActivePublicKeys(neighborhoodId).stream()
                .map(PublicKeyResponse::from)
                .toList();
    }
}
