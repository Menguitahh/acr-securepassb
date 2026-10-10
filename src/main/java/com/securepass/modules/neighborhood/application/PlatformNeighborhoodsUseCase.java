package com.securepass.modules.neighborhood.application;

import com.securepass.modules.identity.application.UserRepository;
import com.securepass.shared.exception.UserOnboardingConflictException;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * securepassb#258: el duenio de la plataforma lista los barrios y crea uno
 * nuevo con su garita y su primer admin, que recibe el mail de activacion.
 * Mismo aprovisionamiento que el CLI.
 */
@AllArgsConstructor
@Service
public class PlatformNeighborhoodsUseCase {

    private final NeighborhoodCatalog catalog;
    private final NeighborhoodProvisioner provisioner;
    private final UserRepository users;

    public List<NeighborhoodOverview> list() {
        return catalog.overview();
    }

    public NeighborhoodProvisioner.Result create(NeighborhoodProvisioner.Command cmd, String actorName) {
        // El provisioner tambien choca con el UNIQUE, pero con un 400 generico:
        // aca el 409 dice que dato esta tomado.
        users.findByEmail(cmd.adminEmail()).ifPresent(u -> {
            throw new UserOnboardingConflictException("EMAIL_TAKEN", null, "El email del admin ya tiene cuenta");
        });
        users.findByDocument(cmd.adminDni()).ifPresent(u -> {
            throw new UserOnboardingConflictException("DNI_TAKEN", null, "El DNI del admin ya tiene cuenta");
        });
        return provisioner.provision(cmd, null, actorName).result();
    }
}
