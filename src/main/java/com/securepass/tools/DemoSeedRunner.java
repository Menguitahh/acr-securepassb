package com.securepass.tools;

import com.securepass.modules.identity.application.AddFamilyMemberUseCase;
import com.securepass.modules.identity.application.AddWorkerUseCase;
import com.securepass.modules.identity.application.OnboardUserUseCase;
import com.securepass.modules.identity.application.UserRepository;
import com.securepass.modules.identity.domain.Role;
import com.securepass.modules.identity.domain.User;
import com.securepass.modules.identity.domain.UserStatus;
import com.securepass.modules.invitation.application.CreateInvitationUseCase;
import com.securepass.modules.invitation.domain.InvitationType;
import com.securepass.modules.neighborhood.application.CreateLotUseCase;
import com.securepass.modules.neighborhood.application.NeighborhoodProvisioner;
import com.securepass.modules.neighborhood.application.UpdateLotUseCase;
import com.securepass.modules.neighborhood.domain.Lot;
import com.securepass.modules.neighborhood.domain.LotStatus;
import com.securepass.shared.schedule.ScheduleWindow;
import com.securepass.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Datos de demostracion para presentar el proyecto: el duenio de la
 * plataforma, el barrio "Los Pinos" con todas sus cuentas y una semana de
 * movimiento, y "Altos del Sol", que solo existe para verlo desde la
 * plataforma (su admin queda pendiente de activacion).
 *
 * Barrios, lotes, cuentas e invitaciones pasan por los mismos casos de uso
 * que la app; la historia (turnos, accesos, estadias) va por SQL porque es
 * pasado. Mismas barreras que DevSeedRunner: perfil dev y
 * securepass.demo.seed=true. Idempotente: si "los-pinos" existe, no hace nada.
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "securepass.demo.seed", havingValue = "true")
@Order(2)
@RequiredArgsConstructor
public class DemoSeedRunner implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final String SLUG = "los-pinos";
    private static final String NAME = "Los Pinos";
    private static final ZoneId TZ = ZoneId.of("America/Argentina/Mendoza");
    static final String PASSWORD = "LosPinos2026";
    static final String PLATFORM_PASSWORD = "Plataforma2026";

    private final NeighborhoodProvisioner provisioner;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final CreateLotUseCase createLot;
    private final UpdateLotUseCase updateLot;
    private final OnboardUserUseCase onboard;
    private final AddFamilyMemberUseCase addFamily;
    private final AddWorkerUseCase addWorker;
    private final CreateInvitationUseCase createInvitation;
    private final JdbcClient jdbc;

    @Override
    public void run(String... args) {
        if (jdbc.sql("SELECT 1 FROM neighborhoods WHERE slug = :s").param("s", SLUG)
                .query(Integer.class).optional().isPresent()) {
            LOG.info("Demo '{}' ya sembrada. Nada que hacer.", SLUG);
            return;
        }
        LOG.warn("DemoSeedRunner: siembra datos de demostracion con passwords conocidas. Solo desarrollo.");

        platformOwner();

        // ---------- Los Pinos ----------
        NeighborhoodProvisioner.Result pinos = provisioner.provision(new NeighborhoodProvisioner.Command(
                NAME, SLUG, TZ.getId(), "Garita principal",
                "admin@lospinos.example", "Carolina Ruiz", "28111222"), null, "Securepass").result();
        UUID nid = pinos.neighborhoodId();
        UUID gate = pinos.gateId();
        activate(pinos.adminUserId());

        Map<String, UUID> lots = new LinkedHashMap<>();
        String[][] lotData = {
                {"A-01", "Los Aromos", "101"}, {"A-02", "Los Aromos", "103"}, {"A-03", "Los Aromos", "105"},
                {"A-04", "Los Aromos", "107"}, {"A-05", "Los Aromos", "109"}, {"A-06", "Los Aromos", "111"},
                {"B-01", "Las Retamas", "202"}, {"B-02", "Las Retamas", "204"}, {"B-03", "Las Retamas", "206"},
                {"B-04", "Las Retamas", "208"}, {"B-05", "Las Retamas", "210"}, {"B-06", "Las Retamas", "212"},
        };
        for (String[] l : lotData) {
            Lot lot = createLot.create(nid, new CreateLotUseCase.Command(l[0], l[1], l[2]));
            lots.put(l[0], lot.id());
        }
        // B-06 en mora: suspendido (BR-03).
        updateLot.update(nid, lots.get("B-06"), new UpdateLotUseCase.Patch(LotStatus.SUSPENDED, null, null));

        UUID martin = owner("titular@lospinos.example", "Martín Gómez", "30555111", lots.get("A-03"));
        UUID lucia = owner("lucia.fernandez@lospinos.example", "Lucía Fernández", "31666222", lots.get("B-02"));
        owner("andres.molina@lospinos.example", "Andrés Molina", "29777333", lots.get("B-06"));
        UUID guard = guard("guardia@lospinos.example", "Jorge Medina", "27888444");

        CurrentUser martinActor = new CurrentUser(martin, nid, List.of(Role.RESIDENT_OWNER), List.of(lots.get("A-03")));
        UUID laura = addFamily.add(martinActor, lots.get("A-03"), new AddFamilyMemberUseCase.Command(
                "Laura Gómez", "32999555", LocalDate.of(1988, 4, 12), "familiar@lospinos.example", "Esposa", true),
                "Martín Gómez", NAME).userId();
        activate(laura);
        UUID ramon = addWorker.add(martinActor, lots.get("A-03"), new AddWorkerUseCase.Command(
                "Ramón Díaz", "25444666", "trabajador@lospinos.example", null, "Jardinería",
                new ScheduleWindow(List.of(1, 2, 3, 4, 5), LocalTime.of(8, 0), LocalTime.of(17, 0))),
                "Martín Gómez", NAME).userId();
        activate(ramon);

        // Invitaciones vigentes para la demo en vivo.
        Instant now = Instant.now();
        invite(martin, nid, lots.get("A-03"), "Sofía Pérez", "35123456", 2, "Cumpleaños de Laura",
                now.minusSeconds(600), now.plus(Duration.ofHours(8)));
        invite(martin, nid, lots.get("A-03"), "Diego Torres", "33222111", 0, "Plomero",
                now.minusSeconds(600), now.plus(Duration.ofHours(4)));
        invite(lucia, nid, lots.get("B-02"), "Pablo Ríos", "36777888", 1, null,
                now.minusSeconds(600), now.plus(Duration.ofHours(6)));

        history(nid, gate, guard, lots, Map.of("A-03", List.of("Martín Gómez", "Laura Gómez"),
                "B-02", List.of("Lucía Fernández"), "B-06", List.of("Andrés Molina")));

        // ---------- Altos del Sol: solo para verlo desde la plataforma ----------
        NeighborhoodProvisioner.Result altos = provisioner.provision(new NeighborhoodProvisioner.Command(
                "Altos del Sol", "altos-del-sol", TZ.getId(), "Acceso norte",
                "admin@altosdelsol.example", "Federico Paz", "26333444"), null, "Securepass").result();
        for (String code : List.of("1", "2", "3", "4", "5", "6", "7", "8")) {
            createLot.create(altos.neighborhoodId(), new CreateLotUseCase.Command("L-" + code, "Av. del Sol", code));
        }

        LOG.info("Demo sembrada: barrios {} y Altos del Sol. Cuentas en README (Demo).", NAME);
    }

    private void platformOwner() {
        UUID id = users.findByEmail("plataforma@securepass.example").map(User::id).orElseGet(() -> {
            UUID created = UUID.randomUUID();
            users.create(new User(created, "plataforma@securepass.example", null,
                    encoder.encode(PLATFORM_PASSWORD), "Luciano Mengarelli", null, null, UserStatus.ACTIVE));
            return created;
        });
        jdbc.sql("INSERT INTO platform_admins (user_id) VALUES (:u) ON CONFLICT DO NOTHING").param("u", id).update();
    }

    private void activate(UUID userId) {
        users.activateWithPassword(userId, encoder.encode(PASSWORD));
    }

    private UUID owner(String email, String name, String dni, UUID lotId) {
        UUID id = onboard.onboard(lotsNeighborhood(lotId), "Carolina Ruiz", NAME,
                new OnboardUserUseCase.Command(email, name, dni, Role.RESIDENT_OWNER, lotId)).userId();
        activate(id);
        return id;
    }

    private UUID guard(String email, String name, String dni) {
        UUID nid = jdbc.sql("SELECT id FROM neighborhoods WHERE slug = :s").param("s", SLUG).query(UUID.class).single();
        UUID id = onboard.onboard(nid, "Carolina Ruiz", NAME,
                new OnboardUserUseCase.Command(email, name, dni, Role.GUARD, null)).userId();
        activate(id);
        return id;
    }

    private UUID lotsNeighborhood(UUID lotId) {
        return jdbc.sql("SELECT neighborhood_id FROM lots WHERE id = :l").param("l", lotId).query(UUID.class).single();
    }

    private void invite(UUID owner, UUID nid, UUID lotId, String guest, String dni, int companions, String notes,
                        Instant from, Instant until) {
        createInvitation.create(owner, nid, new CreateInvitationUseCase.Command(
                lotId, InvitationType.SINGLE, guest, dni, companions, notes, from, until, 1));
    }

    // ---------- historia: 7 dias de turnos, accesos y estadias ----------

    private static final String[][] GUESTS = {
            {"Valentina Sosa", "34111222"}, {"Tomás Herrera", "33444555"}, {"Camila Rojas", "35666777"},
            {"Nicolás Castro", "32888999"}, {"Agustina Vega", "36111333"}, {"Federico Luna", "31222444"},
            {"Micaela Ortiz", "37333555"}, {"Joaquín Silva", "30444666"},
    };

    private void history(UUID nid, UUID gate, UUID guard, Map<String, UUID> lots, Map<String, List<String>> residents) {
        Random r = new Random(2026);
        LocalDate today = LocalDate.now(TZ);
        Instant now = Instant.now();
        List<String> guestLots = new ArrayList<>(List.of("A-01", "A-02", "A-03", "A-05", "B-01", "B-02", "B-04"));

        for (int d = 6; d >= 0; d--) {
            LocalDate day = today.minusDays(d);
            Instant shiftStart = ZonedDateTime.of(day, LocalTime.of(6, 0), TZ).toInstant();
            Instant shiftEnd = d == 0 ? now.minus(Duration.ofMinutes(20)) : ZonedDateTime.of(day, LocalTime.of(22, 0), TZ).toInstant();
            if (!shiftEnd.isAfter(shiftStart)) continue;
            UUID shift = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO guard_shifts (id, neighborhood_id, gate_id, guard_id, started_at, ended_at, handover_notes)
                    VALUES (:id, :n, :g, :guard, :s, :e, :notes)
                    """).param("id", shift).param("n", nid).param("g", gate).param("guard", guard)
                    .param("s", ts(shiftStart)).param("e", ts(shiftEnd))
                    .param("notes", d == 0 ? "Sin novedades. Queda una visita en A-03 que todavia no salio." : "Sin novedades.")
                    .update();

            // Residentes: salen a la manana y vuelven a la tarde.
            for (var e : residents.entrySet()) {
                for (String name : e.getValue()) {
                    Instant out = at(day, 7 + r.nextInt(3), r.nextInt(60));
                    Instant back = at(day, 17 + r.nextInt(3), r.nextInt(60));
                    if (within(out, shiftStart, shiftEnd)) log(nid, gate, shift, guard, lots.get(e.getKey()), "OUT", "RESIDENT", name, null, 0, null, out);
                    if (within(back, shiftStart, shiftEnd)) log(nid, gate, shift, guard, lots.get(e.getKey()), "IN", "RESIDENT", name, null, 0, null, back);
                }
            }

            // Trabajador: lunes a viernes de 8 a 17.
            int dow = day.getDayOfWeek().getValue();
            if (dow <= 5) {
                Instant in = at(day, 8, 5 + r.nextInt(20));
                Instant out = at(day, 16, 30 + r.nextInt(25));
                if (within(in, shiftStart, shiftEnd)) log(nid, gate, shift, guard, lots.get("A-03"), "IN", "SERVICE", "Ramón Díaz", "25444666", 0, null, in);
                if (within(out, shiftStart, shiftEnd)) log(nid, gate, shift, guard, lots.get("A-03"), "OUT", "SERVICE", "Ramón Díaz", "25444666", 0, null, out);
            } else {
                Instant tried = at(day, 9, 10);
                // Igual que RecordAccessAttemptUseCase: un denegado de trabajador
                // queda como GUEST sin nombre, documento ni lote (#210).
                if (within(tried, shiftStart, shiftEnd)) log(nid, gate, shift, guard, null, "IN", "GUEST", null, null, 0, "OUT_OF_SCHEDULE", tried);
            }

            // Visitas: 4 a 7 por dia, con su estadia.
            int visits = 4 + r.nextInt(4) + (dow >= 6 ? 3 : 0);
            for (int v = 0; v < visits; v++) {
                String[] g = GUESTS[r.nextInt(GUESTS.length)];
                String lot = guestLots.get(r.nextInt(guestLots.size()));
                int companions = r.nextInt(4) == 0 ? 1 + r.nextInt(3) : 0;
                Instant in = at(day, 10 + r.nextInt(9), r.nextInt(60));
                Instant out = in.plus(Duration.ofMinutes(45 + r.nextInt(180)));
                // Entra y sale dentro del turno: las unicas visitas adentro son las de hoy, abajo.
                if (!within(in, shiftStart, shiftEnd) || !within(out, shiftStart, shiftEnd)) continue;
                UUID entry = log(nid, gate, shift, guard, lots.get(lot), "IN", "GUEST", g[0], g[1], companions, null, in);
                UUID exit = log(nid, gate, shift, guard, lots.get(lot), "OUT", "GUEST", g[0], g[1], companions, null, out);
                stay(nid, lots.get(lot), g[0], g[1], companions, entry, exit, in, out, in.plus(Duration.ofHours(4)));
            }

            // Denegados: un pase vencido o revocado cada tanto, y el lote en mora.
            Instant expired = at(day, 15, r.nextInt(60));
            Instant revoked = at(day, 19, r.nextInt(60));
            Instant suspended = at(day, 12, r.nextInt(60));
            if (r.nextInt(2) == 0 && within(expired, shiftStart, shiftEnd))
                log(nid, gate, shift, guard, lots.get("A-05"), "IN", "GUEST", "Martina Paz", "38111999", 0, "EXPIRED", expired);
            if (d % 3 == 0 && within(revoked, shiftStart, shiftEnd))
                log(nid, gate, shift, guard, lots.get("B-01"), "IN", "GUEST", "Sebastián Gil", "37222888", 0, "REVOKED", revoked);
            if (d % 2 == 1 && within(suspended, shiftStart, shiftEnd))
                log(nid, gate, shift, guard, lots.get("B-06"), "IN", "GUEST", "Hernán Acosta", "36555444", 0, "LOT_SUSPENDED", suspended);
        }

        // Hoy: una visita adentro en horario y otra que ya paso el tiempo de alerta.
        Instant shiftToday = jdbc.sql("""
                SELECT started_at FROM guard_shifts WHERE neighborhood_id = :n ORDER BY started_at DESC LIMIT 1
                """).param("n", nid).query(Timestamp.class).single().toInstant();
        UUID lastShift = jdbc.sql("SELECT id FROM guard_shifts WHERE neighborhood_id = :n ORDER BY started_at DESC LIMIT 1")
                .param("n", nid).query(UUID.class).single();
        Instant recent = later(shiftToday, now.minus(Duration.ofMinutes(50)));
        // Permanencia: pasa el tiempo de alerta del barrio (stay_alert_minutes, 8 h por defecto).
        Instant longAgo = now.minus(Duration.ofHours(9));
        UUID longAgoShift = longAgo.isBefore(shiftToday) ? null : lastShift;
        UUID e1 = log(nid, gate, lastShift, guard, lots.get("A-01"), "IN", "GUEST", "Marcos Vera", "34999111", 1, null, recent);
        stay(nid, lots.get("A-01"), "Marcos Vera", "34999111", 1, e1, null, recent, null, recent.plus(Duration.ofHours(4)));
        UUID e2 = log(nid, gate, longAgoShift, longAgoShift == null ? null : guard, lots.get("A-03"), "IN", "GUEST",
                "Carla Núñez", "35888222", 0, null, longAgo);
        stay(nid, lots.get("A-03"), "Carla Núñez", "35888222", 0, e2, null, longAgo, null, longAgo.plus(Duration.ofHours(3)));
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a.plusSeconds(60) : b;
    }

    private static Instant at(LocalDate day, int hour, int minute) {
        return ZonedDateTime.of(day, LocalTime.of(Math.min(hour, 23), minute), TZ).toInstant();
    }

    private static boolean within(Instant t, Instant from, Instant to) {
        return !t.isBefore(from) && t.isBefore(to);
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }

    private UUID log(UUID nid, UUID gate, UUID shift, UUID guard, UUID lot, String direction, String subjectType,
                     String name, String document, int companions, String denial, Instant capturedAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO access_logs (id, neighborhood_id, gate_id, shift_id, guard_id, lot_id, direction,
                    subject_type, result, denial_reason, subject_name, subject_document, companions,
                    captured_at, recorded_at, client_event_id)
                VALUES (:id, :n, :g, :s, :guard, :lot, :dir, :type, :result, :denial, :name, :doc, :comp,
                    :at, :at, :ev)
                """)
                .param("id", id).param("n", nid).param("g", gate).param("s", shift).param("guard", guard)
                .param("lot", lot).param("dir", direction).param("type", subjectType)
                .param("result", denial == null ? "ALLOWED" : "DENIED").param("denial", denial)
                .param("name", name).param("doc", document).param("comp", companions)
                .param("at", ts(capturedAt)).param("ev", UUID.randomUUID())
                .update();
        return id;
    }

    private void stay(UUID nid, UUID lot, String name, String document, int companions, UUID entry, UUID exit,
                      Instant enteredAt, Instant exitedAt, Instant expectedUntil) {
        jdbc.sql("""
                INSERT INTO stays (neighborhood_id, lot_id, subject_name, subject_document, companions,
                    entry_log_id, exit_log_id, entered_at, exited_at, expected_until)
                VALUES (:n, :lot, :name, :doc, :comp, :entry, :exit, :in, :out, :until)
                """)
                .param("n", nid).param("lot", lot).param("name", name).param("doc", document)
                .param("comp", companions).param("entry", entry).param("exit", exit)
                .param("in", ts(enteredAt)).param("out", exitedAt == null ? null : ts(exitedAt))
                .param("until", ts(expectedUntil))
                .update();
    }
}
