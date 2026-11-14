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
    public void preservesConfirmedAndUncertainOutcomesAndRequestedPlayers() throws Exception {
        TeeOneAutomation automation = mock(TeeOneAutomation.class);
        when(automation.getBookingHolder()).thenReturn(Map.of("name", "Holder", "licencia", "DEMO-1"));
        when(automation.getObservedSocioNames()).thenReturn(Map.of("DEMO-2", "Socio"));
        BookingData data = new BookingData("20261115", "Rosa", "08:30", 2, List.of("DEMO-2"));
        ZonedDateTime start = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        when(automation.isReservationSubmitted()).thenReturn(true);
        Map<String, Object> uncertain = BookingReport.create(data, LocalDate.of(2026,11,15), automation, start);
        assertEquals("Uncertain", uncertain.get("outcome"));
        assertNull(uncertain.get("booked"));
        assertEquals(2, ((List<?>) uncertain.get("participants")).size());
        when(automation.getReservationSuccess()).thenReturn(true);
        when(automation.getSelectedRecorrido()).thenReturn("Amarillo");
        when(automation.getSelectedHoraDeJuego()).thenReturn("08:25");
        assertEquals("Confirmed", BookingReport.create(data, LocalDate.of(2026,11,15), automation, start).get("outcome"));
        Path output = Path.of("target", "dashboard-fixture.json");
        BookingReport.write(output, uncertain);
        Map<?, ?> decoded = GsonFactory.getDefaultInstance().fromString(Files.readString(output), Map.class);
        assertEquals("Uncertain", decoded.get("outcome"));
        assertFalse(Files.readString(output).contains("password"));
    }

    @Test
    public void distinguishesUnavailableFromTechnicalFailure() {
        TeeOneAutomation automation = mock(TeeOneAutomation.class);
        assertEquals("Technical failure", BookingReport.outcome(automation));
        when(automation.isBookingUnavailable()).thenReturn(true);
        assertEquals("Unavailable", BookingReport.outcome(automation));
        assertEquals("Technical failure", BookingReport.outcome(null));
    }
}