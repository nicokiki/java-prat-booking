package com.prat.booking;

import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads configuration from .env file.
 */
public class Config {

    private static final Dotenv DOTENV = Dotenv.configure()
            .ignoreIfMissing()
            .load();

    public static String teeOneUsername() {
        return require("TEEONE_USERNAME");
    }

    public static String teeOnePassword() {
        return require("TEEONE_PASSWORD");
    }

    public static Path googleCredentialsPath() {
        String path = require("GOOGLE_CREDENTIALS_PATH");
        return Paths.get(path);
    }

    public static String googleSheetId() {
        return require("GOOGLE_SHEET_ID");
    }

    public static String emailFrom() {
        return require("EMAIL_FROM");
    }

    public static String emailPassword() {
        return require("EMAIL_PASSWORD");
    }

    public static String emailTo() {
        return get("EMAIL_TO", "nicogonzalez@gmail.com");
    }

    public static String emailSmtpHost() {
        return get("EMAIL_SMTP_HOST", "smtp.gmail.com");
    }

    public static int emailSmtpPort() {
        return Integer.parseInt(get("EMAIL_SMTP_PORT", "587"));
    }

    public static boolean emailStartTls() {
        return !"false".equalsIgnoreCase(get("EMAIL_SMTP_STARTTLS", "true"));
    }

    public static boolean emailSsl() {
        return "true".equalsIgnoreCase(get("EMAIL_SMTP_SSL", "false"));
    }

    public static boolean emailDebug() {
        return "true".equalsIgnoreCase(get("EMAIL_DEBUG", "false"));
    }

    public static int emailTimeoutMs() {
        return Integer.parseInt(get("EMAIL_TIMEOUT_MS", "15000"));
    }

    public static boolean headless() {
        return !"false".equalsIgnoreCase(get("HEADLESS", "true"));
    }

    public static String googleSheetRange() {
        return get("GOOGLE_SHEET_RANGE", "Sheet1!A:Z");
    }

    /** Wait in ms after selecting Jugadores, before Hora de juego. Default 1000. Set higher for debugging. */
    public static int waitAfterJugadoresMs() {
        return Integer.parseInt(get("DEBUG_WAIT_AFTER_JUGADORES_MS", "1000"));
    }

    private static String require(String key) {
        String value = DOTENV.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required env variable: " + key);
        }
        return value.trim();
    }

    private static String get(String key, String defaultValue) {
        String value = DOTENV.get(key);
        return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
    }
}
