package com.securepass.modules.identity.infrastructure.rest;

import com.securepass.modules.identity.application.AddWorkerUseCase;
import com.securepass.modules.identity.application.ListWorkersUseCase;
import com.securepass.modules.identity.application.NeighborhoodNameQuery;
import com.securepass.modules.identity.application.UpdateWorkerUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * WBS-115: gestion de trabajadores de un lote. OWNER puro, sin ADMIN — la
 * administracion de trabajadores es del titular del lote (US-62/63/64), no
 * del barrio. El gate de OWNER contra {@code lot_permissions} garantiza
 * aislamiento cross-barrio: un OWNER de otro barrio jamas pasa el gate para
 * este lotId, porque el lote pertenece a un solo barrio.
 */
@RestController
@RequestMapping("/api/v1/lots/{lotId}/workers")
public class WorkerController {

    private final AddWorkerUseCase addUseCase;
    private final ListWorkersUseCase listUseCase;
    private final UpdateWorkerUseCase updateUseCase;
    private final UserRepository users;
    private final NeighborhoodNameQuery neighborhoods;
    private final AccessGuard accessGuard;

    public WorkerController(
            AddWorkerUseCase addUseCase,
            ListWorkersUseCase listUseCase,
            UpdateWorkerUseCase updateUseCase,
            UserRepository users,
            NeighborhoodNameQuery neighborhoods,
            AccessGuard accessGuard) {
        this.addUseCase = addUseCase;
        this.listUseCase = listUseCase;
        this.updateUseCase = updateUseCase;
        this.users = users;
        this.neighborhoods = neighborhoods;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public List<WorkerResponse> list(@PathVariable UUID lotId) {
        CurrentUser actor = accessGuard.require();
        return listUseCase.list(actor, lotId).stream().map(WorkerResponse::from).toList();
    }

    @PostMapping
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public ResponseEntity<AddWorkerResponse> add(
            @PathVariable UUID lotId,
            @Valid @RequestBody AddWorkerRequest request) {
        CurrentUser actor = accessGuard.require();
        AddWorkerUseCase.Result result = addUseCase.add(
                actor, lotId, request.toCommand(),
                actorName(actor),
                neighborhoods.findName(actor.neighborhoodId()));
        AddWorkerResponse body = AddWorkerResponse.from(result);
        if (result.changed()) {
            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .location(URI.create("/api/v1/lots/" + lotId + "/workers/" + result.userId()))
                    .body(body);
        }
        return ResponseEntity.ok(body);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public UpdateWorkerResponse update(
            @PathVariable UUID lotId,
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateWorkerRequest request) {
        CurrentUser actor = accessGuard.require();
        UpdateWorkerUseCase.Result result = updateUseCase.update(
                actor, lotId, userId,
                request.service(),
                request.scheduleOrNull(),
                request.active());
        return UpdateWorkerResponse.from(result);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("@lotPerm.hasAtLeast(#lotId, T(com.securepass.shared.security.LotPermissionType).OWNER)")
    public UpdateWorkerResponse delete(
            @PathVariable UUID lotId,
            @PathVariable UUID userId) {
        // DELETE = baja idempotente (active=false). No borra la fila: el user
        // sigue vivo por si tiene WORKER en otros lotes (US-64).
        CurrentUser actor = accessGuard.require();
        UpdateWorkerUseCase.Result result = updateUseCase.update(
                actor, lotId, userId, null, null, Boolean.FALSE);
        return UpdateWorkerResponse.from(result);
    }

    private String actorName(CurrentUser actor) {
        return users.findById(actor.userId()).map(User::fullName).orElse("Titular");
    }
}
