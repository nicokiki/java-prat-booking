package com.prat.booking;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;
import java.time.ZonedDateTime;
import java.time.LocalDate;

import static org.junit.Assert.*;

public class EmailNotifierTest {
    @Test
    public void confirmationTimeIsMadridTimeInSubjectAndBody() {
        ZonedDateTime confirmedAt = ZonedDateTime.parse("2026-11-08T07:56:10.123Z");
        LocalDate bookingDate = LocalDate.of(2026, 11, 9);

        assertEquals("TeeOne Booking: Success for 09/11/2026 | Confirmed at 08:56:10:123 AM (Madrid)",
            EmailNotifier.successSubject(bookingDate, confirmedAt));
        assertEquals("The tee time was booked successfully.\n\n"
                + "Booking date: 09/11/2026\n"
                + "Booking confirmed at: 08:56:10:123 AM\n"
                + "Confirmation date: 08/11/2026\n"
                        + "Timezone: Europe/Madrid (UTC+01:00)\n\nHorario: 10:00",
            EmailNotifier.successBody("Horario: 10:00", bookingDate, confirmedAt));
    }

    @Test
    public void confirmationTimeUsesMadridSummerOffset() {
        ZonedDateTime confirmedAt = ZonedDateTime.parse("2026-07-08T18:00:05.007Z");

        assertTrue(EmailNotifier.successSubject(LocalDate.of(2026, 7, 10), confirmedAt)
            .contains("Confirmed at 08:00:05:007 PM (Madrid)"));
        assertTrue(EmailNotifier.successBody("Details", null, confirmedAt).contains("UTC+02:00"));
    }

    @Test
    public void missingConfirmationTimePreservesLegacyEmailText() {
        assertEquals("TeeOne Booking: Success", EmailNotifier.successSubject(null, null));
        assertEquals("The tee time was booked successfully.\n\nDetails",
            EmailNotifier.successBody("Details", null, null));
    }

        @Test
        public void midnightAndNoonUseCorrectAmPmAndCalendarYear() {
        LocalDate bookingDate = LocalDate.of(2027, 1, 1);
        assertEquals("TeeOne Booking: Success for 01/01/2027 | Confirmed at 12:00:00:000 AM (Madrid)",
            EmailNotifier.successSubject(bookingDate, ZonedDateTime.parse("2026-12-31T23:00:00Z")));
        assertEquals("TeeOne Booking: Success for 01/01/2027 | Confirmed at 12:00:00:999 PM (Madrid)",
            EmailNotifier.successSubject(bookingDate, ZonedDateTime.parse("2027-01-01T11:00:00.999Z")));
        }

    @Test
    public void successScreenshotIsPngAttachmentWithOriginalDetails() throws Exception {
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        MimeMessage message = message("Booking completed successfully", png);

        assertTrue(message.isMimeType("multipart/mixed"));
        MimeMultipart content = (MimeMultipart) message.getContent();
        assertEquals(2, content.getCount());
        assertEquals("Booking completed successfully", content.getBodyPart(0).getContent());
        BodyPart attachment = content.getBodyPart(1);
        assertEquals("booking-confirmation.png", attachment.getFileName());
        assertEquals("attachment", attachment.getDisposition());
        assertTrue(attachment.isMimeType("image/png"));
        assertArrayEquals(png, attachment.getInputStream().readAllBytes());
    }

    @Test
    public void missingScreenshotKeepsPlainTextEmail() throws Exception {
        MimeMessage message = message("Booking completed successfully", null);

        assertTrue(message.isMimeType("text/plain"));
        assertEquals("Booking completed successfully", message.getContent());
    }

    @Test
    public void emptyScreenshotKeepsPlainTextEmail() throws Exception {
        MimeMessage message = message("Booking completed successfully", new byte[0]);

        assertTrue(message.isMimeType("text/plain"));
        assertEquals("Booking completed successfully", message.getContent());
    }

    private MimeMessage message(String body, byte[] png) throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage message = new MimeMessage(session);
        EmailNotifier.setMessageContent(message, body, png);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        message.writeTo(encoded);
        return new MimeMessage(session, new ByteArrayInputStream(encoded.toByteArray()));
    }
}