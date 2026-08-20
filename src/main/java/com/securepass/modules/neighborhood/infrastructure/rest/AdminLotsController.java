package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.neighborhood.application.AddLotMemberUseCase;
import com.securepass.modules.neighborhood.application.CreateLotUseCase;
import com.securepass.modules.neighborhood.application.ListLotsUseCase;
import com.securepass.modules.neighborhood.application.LotMemberRepository;
import com.securepass.modules.neighborhood.application.UpdateLotUseCase;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotMember;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/lots")
@PreAuthorize("hasRole('ADMIN')")
public class AdminLotsController {

    private final CreateLotUseCase createLot;
    private final UpdateLotUseCase updateLot;
    private final ListLotsUseCase listLots;
    private final AddLotMemberUseCase addMemberUseCase;
    private final LotMemberRepository members;
    private final AccessGuard guard;

    public AdminLotsController(
            CreateLotUseCase createLot,
            UpdateLotUseCase updateLot,
            ListLotsUseCase listLots,
            AddLotMemberUseCase addMemberUseCase,
            LotMemberRepository members,
            AccessGuard guard) {
        this.createLot = createLot;
        this.updateLot = updateLot;
        this.listLots = listLots;
        this.addMemberUseCase = addMemberUseCase;
        this.members = members;
        this.guard = guard;
    }

    @GetMapping
    public List<LotResponse> list(@RequestParam(defaultValue = "0") int page) {
        CurrentUser user = guard.require();
        return listLots.list(user.neighborhoodId(), page).stream().map(LotResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<LotResponse> create(@Valid @RequestBody CreateLotRequest request) {
        CurrentUser user = guard.require();
        Lot lot = createLot.create(user.neighborhoodId(), request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/admin/lots/" + lot.id()))
                .body(LotResponse.from(lot));
    }

    @PatchMapping("/{id}")
    public LotResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateLotRequest request) {
        CurrentUser user = guard.require();
        return LotResponse.from(updateLot.update(user.neighborhoodId(), id, request.toPatch()));
    }

    @GetMapping("/{id}/members")
    public List<LotMemberResponse> members(@PathVariable UUID id) {
        return members.listByLot(id).stream().map(LotMemberResponse::from).toList();
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<LotMemberResponse> addMember(
            @PathVariable UUID id, @Valid @RequestBody AddLotMemberRequest request) {
        CurrentUser user = guard.require();
        LotMember member = addMemberUseCase.add(user.neighborhoodId(), id, request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/admin/lots/" + id + "/members/" + member.id()))
                .body(LotMemberResponse.from(member));
    }
}
