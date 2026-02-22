package com.prat.booking;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reads booking data from Google Sheets for a given date.
 * Expects sheet with columns: Fecha, Recorrido, Horario, Socios, Socio-1, Socio-2, Socio-3
 * Fecha format: yyyyMMdd (e.g. 20260221)
 */
public class GoogleSheetsReader {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsReader.class);
    private static final String DATE_COLUMN = "Fecha";

    private final Sheets sheetsService;
    private final String sheetId;

    public GoogleSheetsReader() throws IOException, GeneralSecurityException {
        this.sheetId = Config.googleSheetId();
        Path credentialsPath = Config.googleCredentialsPath();

        if (!Files.exists(credentialsPath)) {
            throw new IllegalStateException(
                    "Google credentials not found at " + credentialsPath +
                            ". Create a service account and download JSON. See README.md");
        }

        GoogleCredentials credentials = ServiceAccountCredentials
                .fromStream(new FileInputStream(credentialsPath.toFile()))
                .createScoped(Collections.singleton(SheetsScopes.SPREADSHEETS_READONLY));

        this.sheetsService = new Sheets.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials))
                .setApplicationName("TeeOne-Booking")
                .build();
    }

    /**
     * Finds the row for the target date and returns booking data.
     * Target date = today + 2 days.
     */
    public BookingData getBookingForDate(LocalDate targetDate) throws IOException {
        String range = Config.googleSheetRange();
        ValueRange response = sheetsService.spreadsheets().values()
                .get(sheetId, range)
                .execute();

        List<List<Object>> rows = response.getValues();
        if (rows == null || rows.isEmpty()) {
            throw new IllegalStateException("Google Sheet is empty");
        }

        List<String> headers = rows.get(0).stream()
                .map(Object::toString)
                .map(String::trim)
                .collect(Collectors.toList());

        int fechaIdx = findColumnIndex(headers, DATE_COLUMN, "Fecha");
        int recorridoIdx = findColumnIndex(headers, "Recorrido", "Recorrido");
        int horarioIdx = findColumnIndex(headers, "Horario", "Horario");
        int sociosIdx = findColumnIndex(headers, "Socios", "Socios");
        int socio1Idx = findColumnIndex(headers, "Socio-1", "Socio-1");
        int socio2Idx = findColumnIndex(headers, "Socio-2", "Socio-2");
        int socio3Idx = findColumnIndex(headers, "Socio-3", "Socio-3");

        String targetDateYyyyMmDd = targetDate.format(DateTimeFormatter.BASIC_ISO_DATE); // yyyyMMdd

        for (int i = 1; i < rows.size(); i++) {
            List<Object> row = rows.get(i);
            if (row.size() <= fechaIdx) continue;

            String rowDateStr = normalizeDate(row.get(fechaIdx));
            if (!rowDateStr.equals(targetDateYyyyMmDd)) continue;

            String recorrido = getCell(row, recorridoIdx);
            String horario = getCell(row, horarioIdx);
            int socios = parseInt(getCell(row, sociosIdx), 2);

            List<String> sociosToAdd = new ArrayList<>();
            if (socios >= 2) sociosToAdd.add(getCell(row, socio1Idx));
            if (socios >= 3) sociosToAdd.add(getCell(row, socio2Idx));
            if (socios >= 4) sociosToAdd.add(getCell(row, socio3Idx));

            return new BookingData(rowDateStr, recorrido, horario, socios, sociosToAdd);
        }

        throw new IllegalStateException("No row found for date " + targetDate + " in Google Sheet");
    }

    private int findColumnIndex(List<String> headers, String... names) {
        for (String name : names) {
            for (int i = 0; i < headers.size(); i++) {
                if (headers.get(i).equalsIgnoreCase(name)) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("Column not found: " + String.join(", ", names));
    }

    private String getCell(List<Object> row, int index) {
        if (index >= row.size()) return "";
        Object val = row.get(index);
        return val != null ? val.toString().trim() : "";
    }

    private String normalizeDate(Object cellValue) {
        if (cellValue == null) return "";
        String raw = cellValue.toString();
        // Handle numeric values from Google Sheets (e.g. 20260221 or 20260221.0)
        if (cellValue instanceof Number) {
            long n = ((Number) cellValue).longValue();
            return String.valueOf(n);
        }
        return raw.replaceAll("[^0-9]", "");
    }

    private int parseInt(String s, int defaultValue) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
