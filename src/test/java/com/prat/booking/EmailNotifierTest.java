package com.prat.booking;

import jakarta.mail.BodyPart;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.junit.Assert.*;

public class EmailNotifierTest {
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