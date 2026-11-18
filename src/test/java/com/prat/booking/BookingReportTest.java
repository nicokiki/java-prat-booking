package com.prat.booking;

import com.google.api.client.json.gson.GsonFactory;
import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BookingReportTest {
    @Test
    public void confirmedReportPreservesFormStartForBothTimingMetrics() {
        TeeOneAutomation automation = mock(TeeOneAutomation.class);
        ZonedDateTime formStart = ZonedDateTime.parse("2026-11-18T20:00:01+01:00");
        when(automation.getReservationSuccess()).thenReturn(true);
        when(automation.getBookingFormStartedAt()).thenReturn(formStart);
        when(automation.getReservationBlockedAt()).thenReturn(formStart.plusSeconds(4));
        when(automation.getReservationConfirmedAt()).thenReturn(formStart.plusSeconds(10));
        Map<String, Object> report = BookingReport.create(new BookingData("20261120", "TEE 1 ROSA", "08:00", 2, List.of()),
                LocalDate.of(2026, 11, 20), automation, formStart.minusMinutes(20));
        assertEquals("2026-11-18T20:00:01+01:00", report.get("bookingFormStartedAt"));
        assertEquals("2026-11-18T20:00:05+01:00", report.get("blockedAt"));
        assertEquals(10.0, report.get("bookingDurationSeconds"));
    }

    @Test
    public void preservesConfirmedAndUnconfirmedSubmissionSafetyAndRequestedPlayers() throws Exception {
        TeeOneAutomation automation = mock(TeeOneAutomation.class);
        when(automation.getBookingHolder()).thenReturn(Map.of("name", "Holder", "licencia", "DEMO-1"));
        when(automation.getObservedSocioNames()).thenReturn(Map.of("DEMO-2", "Socio"));
        BookingData data = new BookingData("20261115", "Rosa", "08:30", 2, List.of("DEMO-2"));
        ZonedDateTime start = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        when(automation.isReservationSubmitted()).thenReturn(true);
        Map<String, Object> uncertain = BookingReport.create(data, LocalDate.of(2026,11,15), automation, start);
        assertEquals("Technical failure", uncertain.get("outcome"));
        assertEquals("Reservation submission attempted; confirmation not observed. Check TeeOne before retrying.", uncertain.get("summary"));
        assertNull(uncertain.get("booked"));
        assertEquals(2, ((List<?>) uncertain.get("participants")).size());
        when(automation.getReservationSuccess()).thenReturn(true);
        when(automation.getSelectedRecorrido()).thenReturn("Amarillo");
        when(automation.getSelectedHoraDeJuego()).thenReturn("08:25");
        assertEquals("Confirmed", BookingReport.create(data, LocalDate.of(2026,11,15), automation, start).get("outcome"));
        Path output = Path.of("target", "dashboard-fixture.json");
        BookingReport.write(output, uncertain);
        Map<?, ?> decoded = GsonFactory.getDefaultInstance().fromString(Files.readString(output), Map.class);
        assertEquals("Technical failure", decoded.get("outcome"));
        assertFalse(Files.readString(output).contains("password"));
    }

    @Test
    public void distinguishesUnavailableFromTechnicalFailure() {
        TeeOneAutomation automation = mock(TeeOneAutomation.class);
        assertEquals("Technical failure", BookingReport.outcome(automation));
        when(automation.isBookingUnavailable()).thenReturn(true);
        assertEquals("Unavailable", BookingReport.outcome(automation));
        assertEquals("Technical failure", BookingReport.outcome(null));
        Map<String, Object> report = BookingReport.create(null, LocalDate.of(2026,11,17), null,
            ZonedDateTime.parse("2026-11-15T20:00:01+01:00"));
        assertEquals("Technical failure", report.get("outcome"));
        assertNull(report.get("players"));
        assertEquals(List.of(), report.get("participants"));
    }

        @Test
        public void informationalReportHasNoBookingOrParticipantsAndSerializesFixture() throws Exception {
        TeeOneAutomation automation = mock(TeeOneAutomation.class);
        when(automation.isAvailabilityOnly()).thenReturn(true);
        when(automation.isInformationalComplete()).thenReturn(true);
        when(automation.getAvailabilitySnapshots()).thenReturn(List.of("TEE 1 ROSA", "TEE 1 AMARILLO")
            .stream().map(course -> Map.<String, Object>of("course", course, "players", 2, "holes", 18,
                "capturedAt", "2026-11-15T20:00:02+01:00", "options", List.of())).toList());
        Map<String, Object> report = BookingReport.create(null, LocalDate.of(2026,11,17), automation,
            ZonedDateTime.parse("2026-11-15T20:00:01+01:00"));
        assertEquals("Informational", report.get("outcome"));
        assertEquals(2, report.get("players"));
        assertEquals(List.of(), report.get("participants"));
        assertNull(report.get("requested"));
        assertNull(report.get("booked"));
        assertNull(report.get("confirmedAt"));
        assertNull(report.get("blockedAt"));
        BookingReport.write(Path.of("target", "informational-fixture.json"), report);
        when(automation.isInformationalComplete()).thenReturn(false);
        assertEquals("Technical failure", BookingReport.outcome(automation));
        }
}