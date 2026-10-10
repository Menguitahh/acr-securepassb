package com.securepass.modules.neighborhood.application;

import com.securepass.modules.identity.application.IssueActivationTokenUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.TokenPurpose;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.modules.invitation.application.SigningKeyIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WBS-A (issue #129): aprovisiona un barrio nuevo end-to-end en una sola
 * transaccion. Los 4 recursos criticos ({@code neighborhood, signing_key,
 * gate, admin PENDING_ACTIVATION}) se crean juntos o no se crea nada.
 *
 * Idempotencia: falla ruidosamente si name o slug ya existen (pre-check
 * antes de escribir + UNIQUE en DB como red).
 *
 * El {@link Result} devuelve identificadores publicos (userId, neighborhoodId,
 * gateId, signingKeyKid) — el plaintext del token de activacion NO va aca,
 * lo obtiene el caller por separado via {@link #lastActivationToken()} para
 * imprimirlo por stdout. Ese token es credencial: no se loguea ni se persiste
 * fuera del hash en {@code activation_tokens}.
 */
@Service
public class NeighborhoodProvisioner {

    private static final Logger LOG = LoggerFactory.getLogger(NeighborhoodProvisioner.class);
    private static final Pattern SLUG_RX = Pattern.compile("^[a-z0-9-]+$");
    private static final Pattern DNI_RX = Pattern.compile("^\\d{7,8}$");
    private static final Pattern EMAIL_RX = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String DEFAULT_GATE_NAME = "Principal";

    private final JdbcClient jdbc;
    private final SigningKeyIssuer signingKeyIssuer;
    private final UserRepository users;
    private final MembershipGranter memberships;
    private final IssueActivationTokenUseCase activationIssuer;

    public NeighborhoodProvisioner(
            JdbcClient jdbc,
            SigningKeyIssuer signingKeyIssuer,
            UserRepository users,
            MembershipGranter memberships,
            IssueActivationTokenUseCase activationIssuer) {
        this.jdbc = jdbc;
        this.signingKeyIssuer = signingKeyIssuer;
        this.users = users;
        this.memberships = memberships;
        this.activationIssuer = activationIssuer;
    }

    /**
     * @return {@link ProvisionOutcome} con Result + plaintext del token. El
     * plaintext lo consume el CLI y se descarta; no queda en el {@link Result}
     * que puede terminar en logs.
     */
    @Transactional
    public ProvisionOutcome provision(Command cmd) {
        return provision(cmd, null);
    }

    /**
     * Overload que acepta {@link Ids} para fijar los UUIDs. Solo lo usa el
     * seeder de dev (barrio con IDs conocidos para tests y flujos manuales).
     * En prod se llama la variante sin ids y todo es aleatorio.
     */
    @Transactional
    public ProvisionOutcome provision(Command cmd, Ids ids) {
        return provision(cmd, ids, "Provisioning CLI");
    }

    /** securepassb#258: invitedBy es quien aparece en el mail de activacion del admin. */
    @Transactional
    public ProvisionOutcome provision(Command cmd, Ids ids, String invitedBy) {
        if (jdbc.sql("SELECT 1 FROM neighborhoods WHERE slug = :s")
                .param("s", cmd.slug()).query(Integer.class).optional().isPresent()) {
            throw new NeighborhoodAlreadyExistsException("slug", cmd.slug());
        }
        if (jdbc.sql("SELECT 1 FROM neighborhoods WHERE lower(name) = lower(:n)")
                .param("n", cmd.name()).query(Integer.class).optional().isPresent()) {
            throw new NeighborhoodAlreadyExistsException("name", cmd.name());
        }

        UUID neighborhoodId = (ids != null && ids.neighborhoodId() != null) ? ids.neighborhoodId() : UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO neighborhoods (id, name, slug, timezone)
                VALUES (:id, :name, :slug, :tz)
                """)
                .param("id", neighborhoodId)
                .param("name", cmd.name())
                .param("slug", cmd.slug())
                .param("tz", cmd.timezone())
                .update();

        SigningKeyIssuer.IssuedKey signingKey = signingKeyIssuer.issueForNeighborhood(neighborhoodId);

        UUID gateId = (ids != null && ids.gateId() != null) ? ids.gateId() : UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO gates (id, neighborhood_id, name, direction, active)
                VALUES (:id, :nid, :name, 'BOTH', TRUE)
                """)
                .param("id", gateId)
                .param("nid", neighborhoodId)
                .param("name", cmd.gateName())
                .update();

        UUID adminUserId = (ids != null && ids.adminUserId() != null) ? ids.adminUserId() : UUID.randomUUID();
        try {
            users.create(new User(
                    adminUserId, cmd.adminEmail(), null, null,
                    cmd.adminFullName(), cmd.adminDni(), null, UserStatus.PENDING_ACTIVATION));
        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            // Email o DNI ya existente en users (cross-barrio). Rollback via
            // @Transactional. Mensaje claro: no es del provisioning per se,
            // es del admin propuesto que colisiona con otro user.
            throw new IllegalArgumentException(
                    "Admin propuesto colisiona con un user existente (email o DNI): " + dive.getMostSpecificCause().getMessage());
        }
        memberships.grant(adminUserId, neighborhoodId, Role.ADMIN);

        String plaintext = activationIssuer.issue(
                adminUserId, TokenPurpose.ACTIVATION, invitedBy, cmd.name());

        LOG.info("neighborhood.provisioned neighborhoodId={} slug={} gateId={} adminUserId={} signingKeyKid={}",
                neighborhoodId, cmd.slug(), gateId, adminUserId, signingKey.kid());

        Result result = new Result(neighborhoodId, gateId, adminUserId, hex(signingKey.kid()));
        return new ProvisionOutcome(result, plaintext);
    }

    private static String hex(String s) {
        return s == null ? null : s;
    }

    public record Command(
            String name,
            String slug,
            String timezone,
            String gateName,
            String adminEmail,
            String adminFullName,
            String adminDni
    ) {
        public Command {
            name = requireNonBlank(name, "name").trim();
            // Sin normalizar a lowercase: si el operador escribio "MAYUS" el
            // barrio se llamaria distinto de lo que escribio. Fail fast.
            slug = requireNonBlank(slug, "slug").trim();
            if (!SLUG_RX.matcher(slug).matches()) {
                throw new IllegalArgumentException("slug invalido (permitido [a-z0-9-]+): " + slug);
            }
            adminEmail = requireNonBlank(adminEmail, "adminEmail").trim().toLowerCase(Locale.ROOT);
            if (!EMAIL_RX.matcher(adminEmail).matches()) {
                throw new IllegalArgumentException("adminEmail invalido: " + adminEmail);
            }
            adminFullName = requireNonBlank(adminFullName, "adminFullName").trim();
            adminDni = requireNonBlank(adminDni, "adminDni").trim();
            if (!DNI_RX.matcher(adminDni).matches()) {
                throw new IllegalArgumentException("adminDni debe tener 7 u 8 digitos");
            }
            timezone = requireNonBlank(timezone, "timezone").trim();
            try {
                ZoneId.of(timezone);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("timezone invalido (no reconocido por tzdata): " + timezone);
            }
            gateName = (gateName == null || gateName.isBlank()) ? DEFAULT_GATE_NAME : gateName.trim();
        }

        private static String requireNonBlank(String v, String field) {
            if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " requerido");
            return v;
        }
    }

    /**
     * Sin activationToken: si algun caller loguea el {@code Result}, no expone
     * la credencial. El plaintext viaja en {@link ProvisionOutcome} y lo consume
     * el CLI en stdout.
     */
    public record Result(
            UUID neighborhoodId,
            UUID gateId,
            UUID adminUserId,
            String signingKeyKid
    ) {}

    public record ProvisionOutcome(Result result, String activationTokenPlaintext) {}

    /** UUIDs opcionales para fijar (dev-seed). null => aleatorio. */
    public record Ids(UUID neighborhoodId, UUID gateId, UUID adminUserId) {}
}
