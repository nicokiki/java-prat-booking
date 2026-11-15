package com.prat.booking;

import org.junit.Test;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class MainTest {
    @Test
    public void inspectionFailureStillSavesReportClosesBrowserAndSendsNoEmail() throws Exception {
        try (var config = mockStatic(Config.class);
             var reports = mockStatic(BookingReport.class);
             var readers = mockConstruction(GoogleSheetsReader.class, (reader, context) ->
                     when(reader.getBookingForDate(any())).thenThrow(new GoogleSheetsReader.NoBookingForDateException(LocalDate.now())));
             var browsers = mockConstruction(TeeOneAutomation.class, (browser, context) ->
                     doThrow(new IllegalStateException("Fixture inspection failed")).when(browser).performAvailabilityInspection(any()));
             var emails = mockConstruction(EmailNotifier.class)) {
            Main.main(new String[0]);
            TeeOneAutomation browser = browsers.constructed().get(0);
            verify(browser).close();
            verify(browser, never()).performBooking(any());
            assertTrue(emails.constructed().isEmpty());
            reports.verify(() -> BookingReport.saveBestEffort(isNull(), any(), same(browser), any()));
        }
    }

    @Test
    public void matchingRowPreservesSuccessEmailDetailsAndArguments() throws Exception {
        BookingData data = new BookingData("20261117", "TEE 1 ROSA", "08:00", 3, List.of("DEMO-1", "DEMO-2"));
        var blocked = java.time.ZonedDateTime.parse("2026-11-15T20:00:02+01:00");
        var confirmed = blocked.plusSeconds(5);
        byte[] screenshot = new byte[]{1, 2, 3};
        try (var config = mockStatic(Config.class);
             var reports = mockStatic(BookingReport.class);
             var readers = mockConstruction(GoogleSheetsReader.class, (reader, context) -> when(reader.getBookingForDate(any())).thenReturn(data));
             var browsers = mockConstruction(TeeOneAutomation.class, (browser, context) -> {
                 when(browser.getReservationSuccess()).thenReturn(true);
                 when(browser.getSelectedRecorrido()).thenReturn("TEE 1 AMARILLO");
                 when(browser.getSelectedHoraDeJuego()).thenReturn("08:15");
                 when(browser.getHoraDeJuegoDropdownOptions()).thenReturn("08:15 (3)");
                 when(browser.getReservationScreenshot()).thenReturn(screenshot);
                 when(browser.getReservationBlockedAt()).thenReturn(blocked);
                 when(browser.getReservationConfirmedAt()).thenReturn(confirmed);
             });
             var emails = mockConstruction(EmailNotifier.class)) {
            Main.main(new String[0]);
            verify(emails.constructed().get(0)).sendSuccess(
                    "Fecha: 17/11/2026\nRecorrido: TEE 1 AMARILLO\nHorario: 08:15\nSocios: 3\n\nHora de juego (opciones en desplegable):\n08:15 (3)",
                    screenshot, confirmed, LocalDate.of(2026, 11, 17), "08:15", "TEE 1 AMARILLO", blocked);
            verify(emails.constructed().get(0), never()).sendFailure(any());
        }
    }

    @Test
    public void missingRowInspectsAvailabilityWithoutBookingOrEmail() throws Exception {
        try (var config = mockStatic(Config.class);
             var reports = mockStatic(BookingReport.class);
             var readers = mockConstruction(GoogleSheetsReader.class, (reader, context) ->
                     when(reader.getBookingForDate(any())).thenThrow(new GoogleSheetsReader.NoBookingForDateException(LocalDate.now())));
             var browsers = mockConstruction(TeeOneAutomation.class);
             var emails = mockConstruction(EmailNotifier.class)) {
            Main.main(new String[0]);
            TeeOneAutomation browser = browsers.constructed().get(0);
            verify(browser).performAvailabilityInspection(any(LocalDate.class));
            verify(browser, never()).performBooking(any());
            verify(browser).close();
            assertTrue(emails.constructed().isEmpty());
            reports.verify(() -> BookingReport.saveBestEffort(isNull(), any(), same(browser), any()));
        }
    }

    @Test
    public void sheetsFailureStillSendsExistingFailureEmailWithoutInspection() throws Exception {
        try (var config = mockStatic(Config.class);
             var reports = mockStatic(BookingReport.class);
             var readers = mockConstruction(GoogleSheetsReader.class, (reader, context) ->
                     when(reader.getBookingForDate(any())).thenThrow(new IOException("Fixture Sheets failure")));
             var browsers = mockConstruction(TeeOneAutomation.class);
             var emails = mockConstruction(EmailNotifier.class)) {
            Main.main(new String[0]);
            assertTrue(browsers.constructed().isEmpty());
            verify(emails.constructed().get(0)).sendFailure("Reservation failed. Site message: Fixture Sheets failure");
        }
    }

    @Test
    public void matchingRowStillBooksAndSendsExistingFailureNotification() throws Exception {
        BookingData data = new BookingData("20261117", "TEE 1 ROSA", "08:00", 3, List.of("DEMO-1", "DEMO-2"));
        try (var config = mockStatic(Config.class);
             var reports = mockStatic(BookingReport.class);
             var readers = mockConstruction(GoogleSheetsReader.class, (reader, context) -> when(reader.getBookingForDate(any())).thenReturn(data));
             var browsers = mockConstruction(TeeOneAutomation.class);
             var emails = mockConstruction(EmailNotifier.class)) {
            Main.main(new String[0]);
            verify(browsers.constructed().get(0)).performBooking(same(data));
            verify(browsers.constructed().get(0), never()).performAvailabilityInspection(any());
            verify(emails.constructed().get(0)).sendFailure("Reservation failed (no success message). Check page source.");
        }
    }
}