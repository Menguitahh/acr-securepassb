package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.application.SearchLotsForGuardUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Autocomplete de lotes para la garita. Necesario porque
 * `POST /access/manual` recibe `lotId` UUID y el guardia solo conoce el
 * codigo del lote. Sin este endpoint el ingreso manual es inusable.
 */
@RestController
@RequestMapping("/api/v1/lots")
public class GuardLotsController {

    private final SearchLotsForGuardUseCase useCase;
    private final AccessGuard guard;

    public GuardLotsController(SearchLotsForGuardUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    // WBS-36: todos los lotes activos, para el ingreso manual sin red.
    @GetMapping("/active")
    @PreAuthorize("hasRole('GUARD')")
    public List<LotAutocompleteResponse> active() {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return useCase.listActive(user.neighborhoodId(), user.userId()).stream()
                .map(LotAutocompleteResponse::from)
                .toList();
    }

    @GetMapping
    @PreAuthorize("hasRole('GUARD')")
    public List<LotAutocompleteResponse> search(@RequestParam("q") String q) {
        CurrentUser user = guard.require();
        if (user.neighborhoodId() == null) throw new AccessDeniedException("usuario sin barrio");
        return useCase.search(user.neighborhoodId(), user.userId(), q)
                .stream()
                .map(LotAutocompleteResponse::from)
                .toList();
    }
}
