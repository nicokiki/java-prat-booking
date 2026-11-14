package com.prat.booking;

import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BookingReport {
    private static final Logger log = LoggerFactory.getLogger(BookingReport.class);

    private BookingReport() {}

    public static Map<String, Object> create(BookingData data, LocalDate targetDate,
            TeeOneAutomation automation, ZonedDateTime startedAt) {
        String runId = System.getenv("GITHUB_RUN_ID");
        String attempt = System.getenv("GITHUB_RUN_ATTEMPT");
        String executionId = runId == null ? "local-" + UUID.randomUUID() : runId + "-" + (attempt == null ? "1" : attempt);
        String outcome = outcome(automation);
        LocalDate playDate = targetDate;
        if (data != null && data.getFecha() != null && !data.getFecha().isBlank()) {
            try {
                playDate = LocalDate.parse(data.getFecha().trim(), DateTimeFormatter.BASIC_ISO_DATE);
            } catch (RuntimeException exception) {
                log.warn("Invalid sheet date; report uses the target date");
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        result.put("executionId", executionId);
        result.put("executionDate", startedAt.toLocalDate().toString());
        result.put("startedAt", timestamp(startedAt));
        result.put("finishedAt", timestamp(ZonedDateTime.now(ZoneId.of("Europe/Madrid"))));
        result.put("playDate", playDate.toString());
        result.put("players", data == null ? null : data.getSocios());
        result.put("requested", data == null ? null : data.getRecorrido() + " " + data.getHorario());
        result.put("outcome", outcome);
        result.put("booked", "Confirmed".equals(outcome)
                ? automation.getSelectedRecorrido() + " " + automation.getSelectedHoraDeJuego() : null);
        result.put("blockedAt", automation == null ? null : timestamp(automation.getReservationBlockedAt()));
        result.put("confirmedAt", automation == null ? null : timestamp(automation.getReservationConfirmedAt()));
        Double duration = automation != null && automation.getBookingFormStartedAt() != null
                && automation.getReservationConfirmedAt() != null
                ? Duration.between(automation.getBookingFormStartedAt(), automation.getReservationConfirmedAt()).toMillis() / 1000.0 : null;
        result.put("bookingDurationSeconds", duration);
        result.put("summary", switch (outcome) {
            case "Confirmed" -> "Reservation success message observed.";
            case "Unavailable" -> "No eligible tee time on the checked courses. Reservation not submitted.";
            case "Uncertain" -> "Reservation submission attempted; confirmation not observed. Check TeeOne before retrying.";
            default -> "Booking stopped before reservation submission. Check the workflow logs.";
        });
        List<Map<String, Object>> participants = new ArrayList<>();
        Map<String, Object> holder = new LinkedHashMap<>();
        holder.put("role", "booking-holder");
        holder.put("name", automation == null ? null : automation.getBookingHolder().get("name"));
        holder.put("licencia", automation == null ? null : automation.getBookingHolder().get("licencia"));
        participants.add(holder);
        if (data != null) {
            for (String licence : data.getSociosToAdd()) {
                Map<String, Object> participant = new LinkedHashMap<>();
                participant.put("role", "socio");
                participant.put("name", automation == null ? null : automation.getObservedSocioNames().get(licence));
                participant.put("licencia", licence);
                participants.add(participant);
            }
        }
        result.put("participants", participants);
        List<Map<String, Object>> availability = new ArrayList<>();
        if (automation != null) {
            for (Map<String, Object> observation : automation.getAvailabilitySnapshots()) {
                Map<String, Object> snapshot = new LinkedHashMap<>(observation);
                snapshot.put("executionId", executionId);
                snapshot.put("playDate", playDate.toString());
                snapshot.put("selectedTime", java.util.Objects.equals(snapshot.get("course"), automation.getSelectedRecorrido())
                        ? automation.getSelectedHoraDeJuego() : null);
                availability.add(snapshot);
            }
        }
        result.put("availability", availability);
        return result;
    }

    static String outcome(TeeOneAutomation automation) {
        if (automation != null && Boolean.TRUE.equals(automation.getReservationSuccess())) return "Confirmed";
        if (automation != null && automation.isReservationSubmitted()) return "Uncertain";
        if (automation != null && automation.isBookingUnavailable()) return "Unavailable";
        return "Technical failure";
    }

    private static String timestamp(ZonedDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static void saveBestEffort(BookingData data, LocalDate targetDate,
            TeeOneAutomation automation, ZonedDateTime startedAt) {
        try {
            write(Path.of("results", "booking-result.json"), create(data, targetDate, automation, startedAt));
        } catch (Exception exception) {
            log.error("Could not save dashboard result; booking outcome is unchanged", exception);
        }
    }

    static void write(Path path, Map<String, Object> result) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, GsonFactory.getDefaultInstance().toPrettyString(result));
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
}