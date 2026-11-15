package com.prat.booking;

import org.junit.Test;
import java.time.LocalDate;
import java.util.List;
import static org.junit.Assert.*;

public class GoogleSheetsReaderTest {
    private final List<Object> headers = List.of("Fecha", "Recorrido", "Horario", "Socios", "Socio-1", "Socio-2", "Socio-3");
    private final LocalDate target = LocalDate.of(2026, 11, 17);

    @Test
    public void missingDateInValidSheetHasSpecificException() {
        assertThrows(GoogleSheetsReader.NoBookingForDateException.class,
                () -> GoogleSheetsReader.bookingFromRows(List.of(headers), target));
        assertThrows(GoogleSheetsReader.NoBookingForDateException.class,
                () -> GoogleSheetsReader.bookingFromRows(List.of(headers, List.of("20261116")), target));
    }

    @Test
    public void emptyOrMalformedSheetsAreNotMissingDateCases() {
        for (List<List<Object>> rows : List.<List<List<Object>>>of(List.of(), List.of(List.of("Fecha")))) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> GoogleSheetsReader.bookingFromRows(rows, target));
            assertFalse(failure instanceof GoogleSheetsReader.NoBookingForDateException);
        }
    }

    @Test
    public void matchingRowPreservesBookingPreferences() {
        BookingData data = GoogleSheetsReader.bookingFromRows(List.of(headers,
                List.of(20261117, "TEE 1 VERDE", "09:00", "3", "DEMO-1", "DEMO-2", "")), target);
        assertEquals("TEE 1 VERDE", data.getRecorrido());
        assertEquals("09:00", data.getHorario());
        assertEquals(3, data.getSocios());
        assertEquals(List.of("DEMO-1", "DEMO-2"), data.getSociosToAdd());
    }
}