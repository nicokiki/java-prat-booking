package com.prat.booking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;

/**
 * Sends email notifications for booking results.
 */
public class EmailNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    public void sendSuccess(String details) {
        send("TeeOne Booking: Success", "The tee time was booked successfully.\n\n" + details);
    }

    public void sendFailure(String error) {
        send("TeeOne Booking: Failed", "The booking failed.\n\nError: " + error);
    }

    private void send(String subject, String body) {
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

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(Config.emailFrom()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(Config.emailTo()));
            message.setSubject(subject);
            message.setText(body);

            Transport.send(message);
            log.info("Email sent to {}", Config.emailTo());
        } catch (Exception e) {
            log.error("Failed to send email", e);
        }
    }
}
