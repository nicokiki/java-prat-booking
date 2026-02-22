package com.prat.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Main entry point. Runs at 8 PM via cron - performs tee time booking for date + 2 days.
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        LocalDate targetDate = targetDate();
        String dateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        log.info("Starting booking for date: {}", dateStr);

        TeeOneAutomation automation = null;
        try {
            BookingData data = new GoogleSheetsReader().getBookingForDate(targetDate);
            log.info("Booking data: Fecha={}, Recorrido={}, Horario={}, Socios={}",
                    data.getFecha(), data.getRecorrido(), data.getHorario(), data.getSocios());

            automation = new TeeOneAutomation();
            automation.performBooking(data);

            Boolean success = automation.getReservationSuccess();
            if (Boolean.TRUE.equals(success)) {
                String details = String.format("Fecha: %s\nRecorrido: %s\nHorario: %s\nSocios: %d",
                        data.getFecha(), data.getRecorrido(), data.getHorario(), data.getSocios());
                new EmailNotifier().sendSuccess(details);
                log.info("Booking completed successfully");
            } else {
                String alert = automation.getReservationAlertText();
                String error = (alert == null || alert.isBlank())
                        ? "Reservation failed (no success message). Check page source."
                        : "Reservation failed. Site message: " + alert;
                new EmailNotifier().sendFailure(error);
                log.error(error);
            }
        } catch (Exception e) {
            log.error("Booking failed", e);
            new EmailNotifier().sendFailure(e.getMessage());
        } finally {
            if (automation != null) {
                automation.close();
            }
        }
    }

    private static LocalDate targetDate() {
        return LocalDate.now().plusDays(2);
        //return LocalDate.now().plusDays(1);
    }
}
