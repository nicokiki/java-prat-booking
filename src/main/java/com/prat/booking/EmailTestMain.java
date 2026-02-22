package com.prat.booking;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.net.InetAddress;
import java.util.Properties;

/**
 * Simple SMTP diagnostic using .env configuration.
 *
 * Run:
 *   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
 *   mvn -q exec:java -Dexec.mainClass="com.prat.booking.EmailTestMain"
 */
public class EmailTestMain {

    public static void main(String[] args) throws Exception {
        String host = Config.emailSmtpHost();
        int port = Config.emailSmtpPort();
        boolean startTls = Config.emailStartTls();
        boolean ssl = Config.emailSsl();
        boolean debug = Config.emailDebug();
        int timeoutMs = Config.emailTimeoutMs();

        String from = Config.emailFrom();
        String to = Config.emailTo();

        System.out.println("SMTP diagnostic");
        System.out.println("  host: " + host);
        System.out.println("  port: " + port);
        System.out.println("  starttls: " + startTls);
        System.out.println("  ssl: " + ssl);
        System.out.println("  timeoutMs: " + timeoutMs);
        System.out.println("  from: " + maskEmail(from));
        System.out.println("  to: " + maskEmail(to));
        System.out.println("  debug: " + debug);

        // DNS check (helps diagnose host/network issues)
        InetAddress addr = InetAddress.getByName(host);
        System.out.println("  resolved: " + addr.getHostAddress());

        Properties props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.connectiontimeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.timeout", String.valueOf(timeoutMs));
        props.put("mail.smtp.writetimeout", String.valueOf(timeoutMs));

        Session session = Session.getInstance(props);
        session.setDebug(debug);

        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject("TeeOne Booking: SMTP test");
        message.setText("This is a test email sent by EmailTestMain.");

        System.out.println("Connecting and sending...");
        try (Transport transport = session.getTransport("smtp")) {
            transport.connect(host, port, Config.emailFrom(), Config.emailPassword());
            transport.sendMessage(message, message.getAllRecipients());
        }
        System.out.println("OK: email sent");
    }

    private static String maskEmail(String email) {
        if (email == null) return "(null)";
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }
}

