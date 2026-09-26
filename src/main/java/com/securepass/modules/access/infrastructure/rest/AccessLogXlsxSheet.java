package com.securepass.modules.access.infrastructure.rest;

import com.securepass.modules.access.application.AccessLogQuery;
import com.securepass.modules.access.domain.DenialReason;
import com.securepass.modules.access.domain.Direction;
import com.securepass.modules.access.domain.SubjectType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * #195: hoja de la bitacora para leer e imprimir. Todo en castellano, fecha
 * real de Excel en hora del barrio y filas denegadas con fondo en la fila
 * entera mas "Denegado" en negrita, para que se distinga impreso en blanco y
 * negro.
 */
final class AccessLogXlsxSheet {

    private static final String[] HEADERS = {
            "Capturado el", "Código de lote", "Dirección", "Tipo", "Resultado",
            "Motivo de denegación", "Nombre", "Documento", "Acompañantes"};
    // Fijos: autoSizeColumn mide con fuentes AWT (ausentes en una imagen slim)
    // y en streaming solo ve la ventana de filas en memoria.
    private static final int[] WIDTHS = {20, 15, 11, 11, 11, 26, 36, 13, 15};
    private static final byte[] HEADER_FILL = {(byte) 0xD9, (byte) 0xD9, (byte) 0xD9};
    private static final byte[] DENIED_FILL = {(byte) 0xFD, (byte) 0xE2, (byte) 0xE2};
    private static final String DATE_FORMAT = "dd/mm/yyyy hh:mm:ss";

    private final SXSSFSheet sheet;
    private final ZoneId zone;
    private final XSSFCellStyle date;
    private final XSSFCellStyle denied;
    private final XSSFCellStyle deniedDate;
    private final XSSFCellStyle deniedBold;
    private int lastRow;

    AccessLogXlsxSheet(SXSSFWorkbook wb, ZoneId zone) {
        this.zone = zone;
        this.sheet = wb.createSheet("Bitácora");

        Font bold = wb.createFont();
        bold.setBold(true);
        short dateFormat = wb.createDataFormat().getFormat(DATE_FORMAT);

        // Estilos una sola vez por workbook: Excel admite ~64k.
        XSSFCellStyle header = style(wb, HEADER_FILL);
        header.setFont(bold);
        this.date = (XSSFCellStyle) wb.createCellStyle();
        date.setDataFormat(dateFormat);
        this.denied = style(wb, DENIED_FILL);
        this.deniedDate = style(wb, DENIED_FILL);
        deniedDate.setDataFormat(dateFormat);
        this.deniedBold = style(wb, DENIED_FILL);
        deniedBold.setFont(bold);

        Row row = sheet.createRow(0);
        for (int c = 0; c < HEADERS.length; c++) {
            sheet.setColumnWidth(c, WIDTHS[c] * 256);
            Cell cell = row.createCell(c);
            cell.setCellValue(HEADERS[c]);
            cell.setCellStyle(header);
        }
        sheet.createFreezePane(0, 1);

        PrintSetup print = sheet.getPrintSetup();
        print.setLandscape(true);
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        sheet.setFitToPage(true);
        sheet.setRepeatingRows(CellRangeAddress.valueOf("1:1"));
    }

    void append(AccessLogQuery.Item i) {
        Row row = sheet.createRow(++lastRow);
        boolean isDenied = !i.allowed();
        XSSFCellStyle base = isDenied ? denied : null;

        Cell capturedAt = row.createCell(0);
        capturedAt.setCellValue(LocalDateTime.ofInstant(i.capturedAt(), zone));
        capturedAt.setCellStyle(isDenied ? deniedDate : date);
        // Siempre string: POI no evalua una celda string como formula, y el
        // documento conserva los ceros a la izquierda.
        text(row, 1, i.lotCode(), base);
        text(row, 2, label(i.direction()), base);
        text(row, 3, label(i.subjectType()), base);
        text(row, 4, isDenied ? "Denegado" : "Permitido", isDenied ? deniedBold : null);
        text(row, 5, i.denialReason() == null ? null : label(i.denialReason()), base);
        text(row, 6, i.subjectName(), base);
        text(row, 7, i.subjectDocument(), base);
        Cell companions = row.createCell(8);
        companions.setCellValue(i.companions());
        if (base != null) companions.setCellStyle(base);
    }

    /** Autofiltro sobre el rango escrito; va al final porque recien ahi se conoce la ultima fila. */
    void finish() {
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, HEADERS.length - 1));
    }

    private static XSSFCellStyle style(SXSSFWorkbook wb, byte[] rgb) {
        XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
        s.setFillForegroundColor(new XSSFColor(rgb, null));
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }

    private static void text(Row row, int c, String value, XSSFCellStyle style) {
        Cell cell = row.createCell(c);
        if (value != null) cell.setCellValue(value);
        if (style != null) cell.setCellStyle(style);
    }

    // switch sin default: un valor nuevo en el enum rompe la compilacion en
    // vez de salir en ingles.
    private static String label(Direction d) {
        return switch (d) {
            case IN -> "Ingreso";
            case OUT -> "Egreso";
        };
    }

    private static String label(SubjectType t) {
        return switch (t) {
            case GUEST -> "Invitado";
            case RESIDENT -> "Residente";
            case SERVICE -> "Servicio";
            case MANUAL -> "Manual";
        };
    }

    // Mismas etiquetas que el dashboard (securepassf DENIAL_LABELS).
    private static String label(DenialReason r) {
        return switch (r) {
            case INVALID_SIGNATURE -> "Firma inválida";
            case NOT_YET_VALID -> "Pase todavía no vigente";
            case EXPIRED -> "Pase vencido";
            case ALREADY_USED -> "Pase ya usado";
            case REVOKED -> "Pase revocado";
            case OUT_OF_SCHEDULE -> "Fuera de horario";
            case LOT_SUSPENDED -> "Lote suspendido";
            case REPLAY -> "Pase usado dos veces";
            case DOCUMENT_MISMATCH -> "Documento no coincide";
            case UNKNOWN -> "Motivo desconocido";
        };
    }
}
