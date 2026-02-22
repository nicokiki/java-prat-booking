# TeeOne Golf Booking

Automated tee time booking for TeeOne golf. Runs daily at 8:00 PM Spanish time (CET/CEST), books for **date + 2 days**, and reads booking preferences from Google Sheets.

## Requirements

- **Java 17+** (Maven uses this for compilation; set `JAVA_HOME` if needed)
- **Chrome** (for Selenium)
- **Google Cloud** project with Sheets API and a service account
- **Gmail** (or SMTP) for email notifications

## Setup

### 1. Google Sheets

Your sheet must have these columns:

| Fecha | Recorrido | Horario | Socios | Socio-1 | Socio-2 | Socio-3 |
|-------|-----------|---------|--------|---------|---------|---------|
| 2026-02-21 | Campo 1 | 09:00 | 3 | John | Jane | Bob |

- **Fecha**: Date in `yyyyMMdd` format (e.g. `20260221`). Rows are matched to the target date (today + 2 days).
- **Recorrido**, **Horario**, **Socios**: Used for the booking form.
- **Socio-1**, **Socio-2**, **Socio-3**: Names of additional players to add (based on Socios count).

### 2. Google Service Account

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project (or use existing)
3. Enable **Google Sheets API**
4. **APIs & Services** → **Credentials** → **Create Credentials** → **Service Account**
5. Create the service account and download the JSON key
6. Save the JSON as `credentials/google-service-account.json`
7. **Share your Google Sheet** with the service account email (e.g. `xxx@project.iam.gserviceaccount.com`) with **Viewer** access

### 3. Environment

```bash
cp .env.example .env
# Edit .env with your values
```

Required variables:

- `TEEONE_USERNAME`, `TEEONE_PASSWORD` – TeeOne login
- `GOOGLE_CREDENTIALS_PATH` – Path to service account JSON
- `GOOGLE_SHEET_ID` – Sheet ID from the URL
- `EMAIL_FROM`, `EMAIL_PASSWORD` – SMTP credentials (use [Gmail App Password](https://support.google.com/accounts/answer/185833) if 2FA)
- `EMAIL_TO` – Recipient (default: nicogonzalez@gmail.com)

### 4. Build

```bash
# If Maven uses Java 8 by default, set JAVA_HOME:
export JAVA_HOME=$(/usr/libexec/java_home -v 17)  # macOS

mvn clean package
```

### 5. Run

```bash
# From project root (where .env lives)
java -jar target/teeone-booking-jar-with-dependencies.jar
```

Or with Maven:

```bash
mvn exec:java -Dexec.mainClass="com.prat.booking.Main"
```

## Cron (19:59 Spanish time, precise 8 PM flow)

Add to crontab (`crontab -e`):

```cron
CRON_TZ=Europe/Madrid
59 19 * * * cd /Users/ngonzalez/dev/java-prat-booking && /usr/bin/java -jar target/teeone-booking-jar-with-dependencies.jar >> logs/cron.log 2>&1
```

Notes:
- Cron triggers on the minute, so this starts the JVM at **19:59:00**.
- The app then enforces these internal timestamps (Europe/Madrid):
  - **19:59:55**: navigate to the URL and perform `login()`
  - **20:00:01**: start `fillBookingForm()` (1 second after 20:00:00)

## Flow

1. Read row from Google Sheet for **today + 2 days**
2. Open `https://members.teeone.golf/prat/reservas/inscripcion/YYYYMMDD`
3. Log in with username/password
4. Select **Recorrido**, **Número de hoyos** (18), **Jugadores**, **Hora de juego**
5. Click **Bloquear**
6. Add socios (Socio-1, Socio-2, Socio-3) via **Añadir a otro socio**
7. Click **RESERVAR**
8. Verify success message and send email

## Troubleshooting

- **ChromeDriver**: Selenium 4 uses built-in driver management. Ensure Chrome is installed.
- **Headless**: Runs in headless mode by default. Set `HEADLESS=false` in `.env` to see the browser (add support in code if needed).
- **Selectors**: If the page structure differs, update `TeeOneAutomation.java` selectors.
- **Sheet name**: If your sheet tab is not "Sheet1", change `RANGE` in `GoogleSheetsReader.java`.
