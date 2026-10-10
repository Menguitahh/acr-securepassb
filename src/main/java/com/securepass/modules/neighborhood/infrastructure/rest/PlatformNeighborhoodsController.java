package com.securepass.modules.neighborhood.infrastructure.rest;

import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.neighborhood.application.NeighborhoodOverview;
import com.securepass.modules.neighborhood.application.NeighborhoodProvisioner;
import com.securepass.modules.neighborhood.application.PlatformNeighborhoodsUseCase;
import com.securepass.shared.security.AccessGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * securepassb#258: panel del duenio de la plataforma. Para ver un barrio no
 * hay endpoint: el web manda X-Neighborhood-Id a los endpoints del admin
 * (JwtAuthenticationFilter).
 */
@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/platform/neighborhoods")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformNeighborhoodsController {

    private final PlatformNeighborhoodsUseCase useCase;
    private final UserRepository users;
    private final AccessGuard guard;

    @GetMapping
    public List<NeighborhoodOverviewResponse> list() {
        return useCase.list().stream().map(NeighborhoodOverviewResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProvisionedNeighborhoodResponse create(@Valid @RequestBody CreateNeighborhoodRequest req) {
        String actor = users.findById(guard.require().userId()).map(User::fullName).orElse("Securepass");
        NeighborhoodProvisioner.Result r = useCase.create(new NeighborhoodProvisioner.Command(
                req.name(), req.slug(), req.timezone(), req.gateName(),
                req.adminEmail(), req.adminFullName(), req.adminDni()), actor);
        return new ProvisionedNeighborhoodResponse(r.neighborhoodId(), r.adminUserId());
    }

    public record CreateNeighborhoodRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 60) @Pattern(regexp = "[a-z0-9-]+", message = "minusculas, numeros y guiones") String slug,
            @NotBlank @Size(max = 60) String timezone,
            @Size(max = 80) String gateName,
            @NotBlank @Email String adminEmail,
            @NotBlank @Size(max = 140) String adminFullName,
            @NotBlank @Pattern(regexp = "\\d{7,8}", message = "dni de 7 u 8 digitos") String adminDni) {}

    public record ProvisionedNeighborhoodResponse(UUID neighborhoodId, UUID adminUserId) {}

    public record NeighborhoodAdminResponse(String fullName, String email, String status) {}

    public record NeighborhoodOverviewResponse(UUID id, String name, String slug, String timezone,
                                               Instant createdAt, long lotCount,
                                               List<NeighborhoodAdminResponse> admins) {
        static NeighborhoodOverviewResponse from(NeighborhoodOverview n) {
            return new NeighborhoodOverviewResponse(n.id(), n.name(), n.slug(), n.timezone(), n.createdAt(),
                    n.lotCount(), n.admins().stream()
                    .map(a -> new NeighborhoodAdminResponse(a.fullName(), a.email(), a.status())).toList());
        }
    }
}
