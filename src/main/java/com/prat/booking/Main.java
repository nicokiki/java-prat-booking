package com.prat.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Main entry point. Runs at 8 PM via cron - performs tee time booking for {@code today + offset} days
 * (offset defaults to 2; override with {@code BOOKING_TARGET_DATE_OFFSET_DAYS}).
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        LocalDate targetDate = targetDate();
        String dateStr = targetDate.format(DateTimeFormatter.ISO_LOCAL_DATE);

        int offsetDays = Config.bookingTargetDateOffsetDays();
        log.info("Starting booking for date: {} (offset from today: {} days)", dateStr, offsetDays);

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
                details = withHoraDeJuegoDropdownOptions(details, automation);
                new EmailNotifier().sendSuccess(details);
                log.info("Booking completed successfully");
            } else {
                String alert = automation.getReservationAlertText();
                String error = (alert == null || alert.isBlank())
                        ? "Reservation failed (no success message). Check page source."
                        : "Reservation failed. Site message: " + alert;
                error = withHoraDeJuegoDropdownOptions(error, automation);
                new EmailNotifier().sendFailure(error);
                log.error(error);
            }
        } catch (Exception e) {
            log.error("Booking failed", e);
            String failureMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            failureMsg = withHoraDeJuegoDropdownOptions(failureMsg, automation);
            new EmailNotifier().sendFailure(failureMsg);
        } finally {
            if (automation != null) {
                automation.close();
            }
        }
    }

    private static LocalDate targetDate() {
        return LocalDate.now().plusDays(Config.bookingTargetDateOffsetDays());
    }

    private static String withHoraDeJuegoDropdownOptions(String body, TeeOneAutomation automation) {
        if (automation == null) {
            return body;
        }
        String opts = automation.getHoraDeJuegoDropdownOptions();
        if (opts == null || opts.isBlank()) {
            return body;
        }
        return body + "\n\nHora de juego (opciones en desplegable):\n" + opts;
    }
}
