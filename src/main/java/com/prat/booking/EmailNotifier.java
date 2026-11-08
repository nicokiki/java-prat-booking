package com.prat.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.activation.DataHandler;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import java.util.Properties;
import java.util.Locale;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Sends email notifications for booking results.
 */
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    public void sendSuccess(String details) {
        sendSuccess(details, null);
    }

    public void sendSuccess(String details, byte[] reservationScreenshot) {
        sendSuccess(details, reservationScreenshot, null);
    }

    public void sendSuccess(String details, byte[] reservationScreenshot, ZonedDateTime confirmedAt) {
        sendSuccess(details, reservationScreenshot, confirmedAt, null);
    }

    public void sendSuccess(String details, byte[] reservationScreenshot, ZonedDateTime confirmedAt,
                            LocalDate bookingDate) {
        send(successSubject(bookingDate, confirmedAt), successBody(details, bookingDate, confirmedAt),
                reservationScreenshot);
    }

    static String successSubject(LocalDate bookingDate, ZonedDateTime confirmedAt) {
        return "TeeOne Booking: Success" + (bookingDate == null ? "" : " for " + formatDate(bookingDate))
                + (confirmedAt == null ? ""
                : " | Confirmed at " + formatConfirmationTime(confirmedAt) + " (Madrid)");
    }

    static String successBody(String details, LocalDate bookingDate, ZonedDateTime confirmedAt) {
        String confirmation = bookingDate == null ? "" : "Booking date: " + formatDate(bookingDate) + "\n";
        if (confirmedAt != null) {
            ZonedDateTime madrid = confirmedAt.withZoneSameInstant(ZoneId.of("Europe/Madrid"));
            confirmation += "Booking confirmed at: " + formatConfirmationTime(madrid)
                    + "\nConfirmation date: " + formatDate(madrid.toLocalDate())
                    + "\nTimezone: Europe/Madrid (UTC" + madrid.getOffset() + ")\n";
        }
        if (!confirmation.isEmpty()) confirmation += "\n";
        return "The tee time was booked successfully.\n\n" + confirmation + details;
    }

    private static String formatDate(LocalDate date) {
        return date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.ENGLISH));
    }

    private static String formatConfirmationTime(ZonedDateTime confirmedAt) {
        return confirmedAt.withZoneSameInstant(ZoneId.of("Europe/Madrid"))
                .format(DateTimeFormatter.ofPattern("hh:mm:ss:SSS a", Locale.ENGLISH));
    }

    public void sendFailure(String error) {
        send("TeeOne Booking: Failed", "The booking failed.\n\nError: " + error, null);
    }

    private void send(String subject, String body, byte[] reservationScreenshot) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", Config.emailSmtpHost());
            props.put("mail.smtp.port", String.valueOf(Config.emailSmtpPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", String.valueOf(Config.emailStartTls()));
            props.put("mail.smtp.ssl.enable", String.valueOf(Config.emailSsl()));
            props.put("mail.smtp.connectiontimeout", String.valueOf(Config.emailTimeoutMs()));
            props.put("mail.smtp.timeout", String.valueOf(Config.emailTimeoutMs()));
            props.put("mail.smtp.writetimeout", String.valueOf(Config.emailTimeoutMs()));

            Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(Config.emailFrom(), Config.emailPassword());
                }
            });
            session.setDebug(Config.emailDebug());

            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(Config.emailFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(Config.emailTo()));
            message.setSubject(subject);
            setMessageContent(message, body, reservationScreenshot);

            Transport.send(message);
            log.info("Email sent to {}", Config.emailTo());
        } catch (Exception e) {
            log.error("Failed to send email", e);
        }
    }

    static void setMessageContent(MimeMessage message, String body, byte[] reservationScreenshot)
            throws jakarta.mail.MessagingException {
        if (reservationScreenshot == null || reservationScreenshot.length == 0) {
            message.setText(body, "UTF-8");
            return;
        }

        MimeBodyPart text = new MimeBodyPart();
        text.setText(body, "UTF-8");
        MimeBodyPart screenshot = new MimeBodyPart();
        screenshot.setDataHandler(new DataHandler(new ByteArrayDataSource(reservationScreenshot, "image/png")));
        screenshot.setFileName("booking-confirmation.png");
        screenshot.setDisposition(MimeBodyPart.ATTACHMENT);

        MimeMultipart content = new MimeMultipart("mixed");
        content.addBodyPart(text);
        content.addBodyPart(screenshot);
        message.setContent(content);
    }
}
