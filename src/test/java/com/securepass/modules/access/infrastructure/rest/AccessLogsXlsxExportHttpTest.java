package com.securepass.modules.access.infrastructure.rest;

import com.securepass.TestcontainersConfiguration;
import com.securepass.modules.access.application.AccessLogQuery;
import com.securepass.modules.access.application.ExportAccessLogsUseCase;
import com.securepass.modules.access.application.ExportFormat;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import com.securepass.modules.identity.domain.Role;
import com.securepass.shared.security.CurrentUser;
import com.securepass.shared.security.JwtService;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * #195: GET /api/v1/admin/reports/access-logs.xlsx. Mismo rango, aislamiento,
 * slot y auditoria que el CSV; archivo para leer en castellano, con fechas de
 * Excel, y temporales de POI cifrados que no sobreviven a ningun camino.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "securepass.export.xlsx-max-rows=3")
class AccessLogsXlsxExportHttpTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-10T00:00:00Z");
    // DefaultTempFileCreationStrategy de POI.
    private static final File POI_TMP = new File(System.getProperty("java.io.tmpdir"), "poifiles");
    private static final String SECRET_NAME = "Secreto Apellido";
    private static final String SECRET_DOC = "99887766";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ExportAccessLogsUseCase exports;
    @SpyBean AccessLogQuery logs;

    UUID barrio;
    UUID otroBarrio;
    UUID gate;
    UUID lot;
    UUID adminId;

    @BeforeEach
    void seed() {
        barrio = UUID.randomUUID();
        otroBarrio = UUID.randomUUID();
        gate = UUID.randomUUID();
        UUID otraGate = UUID.randomUUID();
        lot = UUID.randomUUID();
        adminId = UUID.randomUUID();

        for (UUID n : List.of(barrio, otroBarrio)) {
            jdbc.update("INSERT INTO neighborhoods (id, name, slug, timezone) VALUES (?, 'B', ?, 'America/Argentina/Mendoza')",
                    n, "b-" + UUID.randomUUID());
        }
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", gate, barrio);
        jdbc.update("INSERT INTO gates (id, neighborhood_id, name, direction) VALUES (?, ?, 'G', 'BOTH')", otraGate, otroBarrio);
        jdbc.update("INSERT INTO lots (id, neighborhood_id, code) VALUES (?, ?, 'L-12')", lot, barrio);
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, full_name, status)
                VALUES (?, ?, 'h', 'admin', 'ACTIVE')
                """, adminId, "a-" + adminId + "@t.com");

        insertLog(barrio, gate, lot, "ALLOWED", null, "Muñoz, \"Tito\"", "00123456", "2026-09-02T10:00:00Z");
        insertLog(barrio, gate, lot, "DENIED", "EXPIRED", "=HYPERLINK(\"x\")", "30111222", "2026-09-03T10:00:00Z");
        insertLog(barrio, gate, null, "ALLOWED", null, "fuera de rango", "30111222", "2026-09-11T10:00:00Z");
        insertLog(otroBarrio, otraGate, null, "ALLOWED", null, "otro barrio", "30111222", "2026-09-02T10:00:00Z");
    }

    @Test
    void exportaXlsxLegibleEnCastellanoYAuditaElFormato() throws Exception {
        HttpResponse<byte[]> resp = export(range());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Type"))
                .hasValue("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(resp.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"access-logs-2026-08-31-2026-09-09.xlsx\"");

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.body()))) {
            XSSFSheet sh = wb.getSheetAt(0);
            assertThat(texts(sh.getRow(0))).containsExactly(
                    "Capturado el", "Código de lote", "Dirección", "Tipo", "Resultado",
                    "Motivo de denegación", "Nombre", "Documento", "Acompañantes", "Pase de");
            assertThat(sh.getRow(0).getCell(0).getCellStyle().getFont().getBold()).isTrue();
            assertThat(sh.getLastRowNum()).isEqualTo(2);

            XSSFRow ok = sh.getRow(1);
            // Fecha real de Excel en hora del barrio (Mendoza, -03:00).
            assertThat(ok.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(DateUtil.isCellDateFormatted(ok.getCell(0))).isTrue();
            assertThat(ok.getCell(0).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.parse("2026-09-02T07:00:00"));
            assertThat(texts(ok).subList(1, 8)).containsExactly(
                    "L-12", "Ingreso", "Invitado", "Permitido", "", "Muñoz, \"Tito\"", "00123456");
            assertThat(ok.getCell(7).getCellType()).isEqualTo(CellType.STRING);
            assertThat(ok.getCell(8).getNumericCellValue()).isEqualTo(1);
            assertThat(texts(ok).get(9)).isEmpty();
            assertThat(fill(ok.getCell(6))).isNull();

            XSSFRow denied = sh.getRow(2);
            assertThat(denied.getCell(4).getStringCellValue()).isEqualTo("Denegado");
            assertThat(denied.getCell(4).getCellStyle().getFont().getBold()).isTrue();
            assertThat(denied.getCell(5).getStringCellValue()).isEqualTo("Pase vencido");
            // Texto literal, nunca formula, y sin la comilla del CSV.
            assertThat(denied.getCell(6).getCellType()).isEqualTo(CellType.STRING);
            assertThat(denied.getCell(6).getStringCellValue()).isEqualTo("=HYPERLINK(\"x\")");
            for (int c = 0; c < 9; c++) {
                assertThat(fill(denied.getCell(c))).as("fondo en la columna %d", c).isEqualTo("FDE2E2");
            }

            assertThat(sh.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sh.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:J3");
            assertThat(sh.getPrintSetup().getLandscape()).isTrue();
            assertThat(sh.getRepeatingRows().formatAsString()).isEqualTo("1:1");
        }

        assertThat(jdbc.queryForObject(
                "SELECT format FROM access_log_exports WHERE actor_user_id = ?", String.class, adminId))
                .isEqualTo("XLSX");
    }

    // #197: [from, to) con to a medianoche; el nombre lleva el ultimo dia
    // elegido y el evento en exactamente to no sale.
    @Test
    void finExclusivoANochePoneElUltimoDiaElegidoYNoIncluyeElBorde() throws Exception {
        insertLog(barrio, gate, lot, "ALLOWED", null, "ultimo", "1", "2026-09-24T02:59:59.999999Z");
        insertLog(barrio, gate, lot, "ALLOWED", null, "en el borde", "2", "2026-09-24T03:00:00Z");

        HttpResponse<byte[]> resp = export("from=2026-09-17T03:00:00Z&to=2026-09-24T03:00:00Z");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Content-Disposition"))
                .hasValue("attachment; filename=\"access-logs-2026-09-17-2026-09-23.xlsx\"");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(resp.body()))) {
            XSSFSheet sh = wb.getSheetAt(0);
            assertThat(sh.getLastRowNum()).isEqualTo(1);
            assertThat(sh.getRow(1).getCell(6).getStringCellValue()).isEqualTo("ultimo");
        }
    }

    @Test
    void sobreElLimiteDeFilasEs422SinAuditar() throws Exception {
        insertLog(barrio, gate, lot, "ALLOWED", null, "a", "1", "2026-09-04T10:00:00Z");
        insertLog(barrio, gate, lot, "ALLOWED", null, "b", "2", "2026-09-05T10:00:00Z");

        HttpResponse<byte[]> resp = export(range());

        assertThat(resp.statusCode()).isEqualTo(422);
        assertThat(new String(resp.body(), StandardCharsets.UTF_8)).contains("CSV");
        assertThat(exportsAudited()).isZero();
    }

    @Test
    void exitoNoDejaTemporales() throws Exception {
        long before = poiTempFiles();

        assertThat(export(range()).statusCode()).isEqualTo(200);

        assertThat(poiTempFiles()).isEqualTo(before);
    }

    // El cursor falla con filas ya escritas al temporal: el body todavia no
    // se comprometio, sale 500, no quedan temporales y se libera el slot.
    // De paso: lo escrito al temporal esta cifrado.
    @Test
    void errorDuranteLaGeneracionNoDejaTemporalesNiSlot() throws Exception {
        long before = poiTempFiles();
        boolean[] plaintext = {true};
        long[] during = {0};
        doAnswer(inv -> {
            Consumer<AccessLogQuery.Item> sink = inv.getArgument(1);
            for (int i = 0; i < 500; i++) sink.accept(item(i));
            during[0] = poiTempFiles() - before;
            plaintext[0] = poiTempContains(SECRET_NAME) || poiTempContains(SECRET_DOC);
            throw new DataAccessResourceFailureException("cursor caido");
        }).when(logs).forEach(any(), any());

        HttpResponse<byte[]> resp = export(range());

        assertThat(resp.statusCode()).isEqualTo(500);
        assertThat(during[0]).as("el temporal existia durante la generacion").isPositive();
        assertThat(plaintext[0]).as("datos personales en claro en el temporal").isFalse();
        assertThat(poiTempFiles()).isEqualTo(before);
        assertSlotsFree();
    }

    // El admin corta la descarga: la escritura de la respuesta falla y los
    // temporales igual se borran.
    @Test
    void corteDeDescargaNoDejaTemporalesNiSlot() throws Exception {
        long before = poiTempFiles();
        doAnswer(inv -> {
            Consumer<AccessLogQuery.Item> sink = inv.getArgument(1);
            for (int i = 0; i < 150_000; i++) sink.accept(item(i));
            return null;
        }).when(logs).forEach(any(), any());

        try (Socket s = new Socket()) {
            s.setReceiveBufferSize(4096);
            s.connect(new java.net.InetSocketAddress("localhost", port));
            OutputStream out = s.getOutputStream();
            out.write(("GET /api/v1/admin/reports/access-logs.xlsx?" + range() + " HTTP/1.1\r\n"
                    + "Host: localhost\r\nAuthorization: Bearer " + token() + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            byte[] head = new byte[1024];
            int n = in.read(head);
            assertThat(new String(head, 0, n, StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 200");
            // RST en vez de FIN: el siguiente write del server falla.
            s.setSoLinger(true, 0);
        }

        long deadline = System.nanoTime() + 30_000_000_000L;
        while (poiTempFiles() != before && System.nanoTime() < deadline) Thread.sleep(100);
        assertThat(poiTempFiles()).isEqualTo(before);
        assertSlotsFree();
    }

    private void assertSlotsFree() {
        CurrentUser admin = new CurrentUser(adminId, barrio, List.of(Role.ADMIN), List.of());
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (true) {
            try (var a = exports.start(admin, FROM, TO, null, null, ExportFormat.CSV);
                 var b = exports.start(admin, FROM, TO, null, null, ExportFormat.CSV)) {
                return;
            } catch (RuntimeException e) {
                if (System.nanoTime() > deadline) throw e;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private AccessLogQuery.Item item(int i) {
        return new AccessLogQuery.Item(UUID.randomUUID(), lot, "L-12", Direction.IN, SubjectType.GUEST,
                i % 10 != 0, i % 10 == 0 ? DenialReason.EXPIRED : null,
                SECRET_NAME + " " + i, SECRET_DOC, 1, FROM.plusSeconds(i), null);
    }

    private static long poiTempFiles() throws IOException {
        if (!POI_TMP.isDirectory()) return 0;
        try (var s = Files.walk(POI_TMP.toPath())) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    private static boolean poiTempContains(String text) throws IOException {
        if (!POI_TMP.isDirectory()) return false;
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        try (var s = Files.walk(POI_TMP.toPath())) {
            for (var p : s.filter(Files::isRegularFile).toList()) {
                if (indexOf(Files.readAllBytes(p), needle) >= 0) return true;
            }
        }
        return false;
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static List<String> texts(XSSFRow row) {
        return java.util.stream.IntStream.range(0, 10)
                .mapToObj(c -> row.getCell(c) == null ? "" : row.getCell(c).toString())
                .toList();
    }

    private static String fill(XSSFCell cell) {
        var color = cell.getCellStyle().getFillForegroundColorColor();
        return color == null ? null : color.getARGBHex().substring(2);
    }

    private void insertLog(UUID nid, UUID gateId, UUID lotId, String result, String reason, String name, String doc, String at) {
        jdbc.update("""
                INSERT INTO access_logs (
                    id, neighborhood_id, gate_id, lot_id, direction, subject_type, result, denial_reason,
                    subject_name, subject_document, companions, captured_at, client_event_id
                ) VALUES (?, ?, ?, ?, 'IN', 'GUEST', ?, ?, ?, ?, 1, ?, ?)
                """, UUID.randomUUID(), nid, gateId, lotId, result, reason, name, doc,
                Timestamp.from(Instant.parse(at)), UUID.randomUUID());
    }

    private String token() {
        return jwt.issue(adminId, barrio, List.of(Role.ADMIN), List.of(lot)).token();
    }

    private HttpResponse<byte[]> export(String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/admin/reports/access-logs.xlsx?" + query))
                .header("Authorization", "Bearer " + token())
                .GET().build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private String range() {
        return "from=" + FROM + "&to=" + TO;
    }

    private int exportsAudited() {
        return jdbc.queryForObject("SELECT count(*) FROM access_log_exports WHERE actor_user_id = ?", Integer.class, adminId);
    }
}
