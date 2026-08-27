package com.securepass.tools;

import com.securepass.SecurepassApplication;
import com.securepass.modules.neighborhood.application.NeighborhoodAlreadyExistsException;
import com.securepass.modules.neighborhood.application.NeighborhoodProvisioner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Entrypoint del comando Gradle {@code provisionNeighborhood}. Solo parsea
 * argumentos y delega al bean {@link NeighborhoodProvisioner}. Cero logica
 * de dominio aca.
 *
 * Uso via Gradle:
 *   ./gradlew provisionNeighborhood -Pname="X" -Pslug="x" -PadminEmail=...
 *
 * Exit codes:
 *   0 = OK
 *   1 = error generico (config, validacion, DB, etc.)
 *   2 = barrio ya existe (name o slug duplicado)
 */
public final class NeighborhoodProvisioningCli {

    private NeighborhoodProvisioningCli() {}

    public static void main(String[] args) {
        Map<String, String> params = parseArgs(args);

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SecurepassApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            NeighborhoodProvisioner provisioner = ctx.getBean(NeighborhoodProvisioner.class);
            NeighborhoodProvisioner.Command cmd = new NeighborhoodProvisioner.Command(
                    params.get("name"),
                    params.get("slug"),
                    params.get("timezone"),
                    params.get("gate-name"),
                    params.get("admin-email"),
                    params.get("admin-full-name"),
                    params.get("admin-dni"));

            NeighborhoodProvisioner.ProvisionOutcome outcome = provisioner.provision(cmd);
            String activationBase = ctx.getEnvironment().getProperty(
                    "securepass.activation.base-url", "http://localhost:8080");
            printSuccess(cmd, outcome, activationBase);
        } catch (NeighborhoodAlreadyExistsException e) {
            System.err.println("Barrio ya existe (" + e.by() + "=" + e.value() + "). Nada quedo escrito.");
            System.exit(2);
        } catch (IllegalArgumentException e) {
            System.err.println("Argumento invalido: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Fallo el aprovisionamiento: " + e.getMessage());
            System.err.println("Nada quedo escrito.");
            System.exit(1);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) continue;
            int eq = arg.indexOf('=');
            if (eq < 0) continue;
            String key = arg.substring(2, eq);
            String value = arg.substring(eq + 1);
            if (!value.isEmpty()) map.put(key, value);
        }
        return map;
    }

    private static void printSuccess(
            NeighborhoodProvisioner.Command cmd,
            NeighborhoodProvisioner.ProvisionOutcome outcome,
            String activationBase) {
        NeighborhoodProvisioner.Result r = outcome.result();
        String base = trimTrailingSlash(activationBase);
        String encoded = URLEncoder.encode(outcome.activationTokenPlaintext(), StandardCharsets.UTF_8);
        String link = base + "/activar/" + encoded;

        // stdout, NO logger — el link es credencial.
        System.out.println();
        System.out.println("Barrio aprovisionado.");
        System.out.println();
        System.out.println("  neighborhoodId:  " + r.neighborhoodId());
        System.out.println("  slug:            " + cmd.slug());
        System.out.println("  name:            " + cmd.name());
        System.out.println("  timezone:        " + cmd.timezone());
        System.out.println();
        System.out.println("  gateId:          " + r.gateId());
        System.out.println("  gateName:        " + cmd.gateName());
        System.out.println("  signingKeyKid:   " + r.signingKeyKid());
        System.out.println();
        System.out.println("  adminUserId:     " + r.adminUserId());
        System.out.println("  adminEmail:      " + cmd.adminEmail());
        System.out.println("  adminDni:        " + cmd.adminDni());
        System.out.println("  activationLink:  " + link);
        System.out.println();
        System.out.println("El admin recibira un email para completar la activacion.");
        System.out.println("Si no llega, usar el link de arriba.");
    }

    private static String trimTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}
