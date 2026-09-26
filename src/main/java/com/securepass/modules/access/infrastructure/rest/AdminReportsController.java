package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.AccessLogQuery;
import com.securepass.modules.access.application.ExportAccessLogsUseCase;
import com.securepass.shared.security.AccessGuard;
import com.securepass.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * WBS-52 / US-32: export CSV de la bitacora. El barrio sale del JWT, igual
 * que en /admin/metrics: no hay parametro por barrio.
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
@PreAuthorize("hasRole('ADMIN')")
public class AdminReportsController {

    static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final String HEADER =
            "captured_at,lot_code,direction,subject_type,result,denial_reason,subject_name,subject_document,companions\r\n";
    // BOM: sin el, Excel abre el UTF-8 como ANSI y rompe tildes y enies.
    private static final String BOM = "\uFEFF";
    // #192: hora del barrio con offset en cada celda. Formato fijo porque
    // OffsetDateTime.toString omite los segundos en :00 y la fraccion varia.
    // El offset sale de atZone por fila: un rango que cruza un cambio de
    // horario (Mendoza 2008: -02:00 -> -03:00) lleva los dos.
    private static final DateTimeFormatter CAPTURED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public enum Result { ALLOWED, DENIED }

    private final ExportAccessLogsUseCase useCase;
    private final AccessGuard guard;

    public AdminReportsController(ExportAccessLogsUseCase useCase, AccessGuard guard) {
        this.useCase = useCase;
        this.guard = guard;
    }

    // Escribe sincronico sobre la respuesta en vez de StreamingResponseBody:
    // ese dispara un dispatch ASYNC que Spring Security vuelve a autorizar sin
    // SecurityContext (403 con el body ya comprometido). Sigue siendo streaming.
    @GetMapping(value = "/access-logs.csv", produces = "text/csv")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "429", description = "Hay demasiados exports en curso; reintentar tras Retry-After")
    public void accessLogs(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) UUID lotId,
            @RequestParam(required = false) Result result,
            HttpServletResponse response) throws IOException {
        CurrentUser admin = guard.require();
        Boolean allowedOnly = result == null ? null : result == Result.ALLOWED;
        // Valida, toma slot y audita antes de tocar la respuesta: un 422 o 429
        // ya no se puede mandar una vez que empezo el body.
        try (ExportAccessLogsUseCase.Export export = useCase.start(admin, from, to, lotId, allowedOnly)) {
            ZoneId zone = export.zone();
            // #197: to es exclusivo; el ultimo dia es el del ultimo instante incluido.
            LocalDate lastDay = LocalDate.ofInstant(to.minusNanos(1), zone);
            String filename = "access-logs-" + LocalDate.ofInstant(from, zone) + "-" + lastDay + ".csv";
            response.setContentType(TEXT_CSV.toString());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            Writer w = new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8));
            w.write(BOM);
            w.write(HEADER);
            try {
                export.stream(row -> writeRow(w, row, zone));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            w.flush();
        }
    }

    private static void writeRow(Writer w, AccessLogQuery.Item i, ZoneId zone) {
        try {
            w.write(String.join(",",
                    CAPTURED_AT.format(i.capturedAt().atZone(zone)),
                    cell(i.lotCode()),
                    i.direction().name(),
                    i.subjectType().name(),
                    i.allowed() ? "ALLOWED" : "DENIED",
                    i.denialReason() == null ? "" : i.denialReason().name(),
                    cell(i.subjectName()),
                    cell(i.subjectDocument()),
                    Integer.toString(i.companions())));
            w.write("\r\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // RFC 4180 + defensa contra CSV injection (OWASP): subject_name lo tipea
    // el guardia o el residente; una celda que empieza con = + - @ tab o CR
    // Excel la evalua como formula. Se neutraliza con comilla simple.
    static String cell(String value) {
        if (value == null || value.isEmpty()) return "";
        String v = "=+-@\t\r".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (v.chars().noneMatch(c -> c == ',' || c == '"' || c == '\n' || c == '\r')) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
