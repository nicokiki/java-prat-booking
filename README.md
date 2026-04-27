# TeeOne Golf Booking

Automated tee time booking for [TeeOne](https://members.teeone.golf) (Prat). It logs in, fills the inscripción form, and confirms the reserva. Booking preferences come from **Google Sheets**; results are sent by **email**. The app is tuned for **Europe/Madrid** wall-clock behaviour around **20:00** so slots that open on the hour are hit at the right time.

You can run it **locally** (Java + Chrome) or on **GitHub Actions** (scheduled or manual).

## Requirements

- **Java 17+** (Maven; set `JAVA_HOME` if needed)
- **Google Chrome** (Selenium 4 manages the driver)
- **Google Cloud** project with Sheets API and a service account
- **SMTP** (e.g. Gmail with an [app password](https://support.google.com/accounts/answer/185833)) for notifications

## Google Sheets

Expected columns (see `GoogleSheetsReader`):

| Fecha | Recorrido | Horario | Socios | Socio-1 | Socio-2 | Socio-3 |
|-------|-----------|---------|--------|---------|---------|---------|
| 20260221 | TEE 1 AMARILLO | 12:45 | 2 | … | … | … |

- **Fecha**: `yyyyMMdd`. The app selects the row for the **target date** (see [Target date](#target-date) below).
- **Recorrido** / **Horario** / **Socios**: form fields. **Horario** is what you want; the success/failure email also appends the **Hora de juego** options the page showed when that dropdown was read.
- **Socio-1…**: extra players when **Añadir a otro socio** is used.

### Service account

1. [Google Cloud Console](https://console.cloud.google.com/) → enable **Google Sheets API**
2. **Credentials** → **Service account** → download JSON key
3. Locally: e.g. `credentials/google-service-account.json`
4. **Share the sheet** with the service account email (at least Viewer)

## Local environment

```bash
cp .env.example .env
# Edit .env
```

**Required (typical):**

- `TEEONE_USERNAME`, `TEEONE_PASSWORD`
- `GOOGLE_CREDENTIALS_PATH` (e.g. `./credentials/google-service-account.json`)
- `GOOGLE_SHEET_ID`
- `EMAIL_FROM`, `EMAIL_PASSWORD`, `EMAIL_TO` (and SMTP host/port/TLS as needed)

**Optional (see [Optional environment variables](#optional-environment-variables)):** `SKIP_MADRID_TIME_WAIT`, `BOOKING_TARGET_DATE_OFFSET_DAYS`, `HEADLESS`, `WAIT_AFTER_RECORRIDO_MS`, `DEBUG_WAIT_AFTER_JUGADORES_MS`, etc.

## Build and run (local)

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)   # macOS example
mvn clean package assembly:single
java -jar target/teeone-booking-jar-with-dependencies.jar
```

Or: `mvn exec:java -Dexec.mainClass="com.prat.booking.Main"`

## GitHub Actions

Workflow: [`.github/workflows/teeone-book.yml`](.github/workflows/teeone-book.yml) — **TeeOne booking (scheduled)**.

### Triggers

| Trigger | When |
|--------|------|
| **Schedule** | Daily at **19:00** `Europe/Madrid` — this is when GitHub **queues** the workflow (cron + `timezone: Europe/Madrid`). The runner may start **tens of minutes later**; that delay is normal on hosted runners, not a broken timezone. The workflow is set earlier in the evening so the Java process can still run **before** the 20:00 form window in typical conditions. |
| **workflow_dispatch** | Run manually: **Actions** → workflow → **Run workflow**. |

### Repository secrets

Configure these under **Settings → Secrets and variables → Actions** (names must match the workflow):

| Secret | Purpose |
|--------|--------|
| `TEEONE_USERNAME` | TeeOne login |
| `TEEONE_PASSWORD` | TeeOne password |
| `GOOGLE_SHEET_ID` | Sheet ID from URL |
| `GOOGLE_SHEET_RANGE` | A1 notation range (optional; default `Sheet1!A:Z` if empty/omitted in workflow env) |
| `GOOGLE_CREDENTIALS_JSON` | Full service account JSON (single line / stored as secret) |
| `EMAIL_FROM` | SMTP from |
| `EMAIL_PASSWORD` | SMTP password (e.g. app password) |
| `EMAIL_TO` | Recipient |
| `EMAIL_SMTP_HOST` | e.g. `smtp.gmail.com` |
| `EMAIL_SMTP_PORT` | e.g. `587` |
| `EMAIL_SMTP_SSL` | `true` or `false` |
| `EMAIL_SMTP_STARTTLS` | `true` or `false` |

The workflow writes `credentials/google-service-account.json` and a runtime `.env` on the runner, then builds a fat JAR and runs `java -jar target/teeone-booking-jar-with-dependencies.jar`.

### Manual run inputs (workflow_dispatch)

When you click **Run workflow**, you can set:

| Input | Default | Meaning |
|--------|---------|--------|
| **skip_madrid_time_wait** | `true` (checked) | If **true**: do **not** wait for the internal Europe/Madrid “clock” (see [Time window](#time-window-europemadrid)); the flow runs as soon as the JAR starts. If **false**: use the same waits as production. **Scheduled runs always behave as if this is false** (ignores the checkbox). |
| **target_date_offset_days** | `2` | `0` = today, `1` = tomorrow, `2` = in two days — for **which sheet row** and, when the sheet’s **Fecha** is empty, the booking URL date. **Scheduled runs always use `2`.** |

The workflow maps these to process environment variables for the JAR:

- `SKIP_MADRID_TIME_WAIT` = `true` or `false` (manual only; scheduled → always `false`)
- `BOOKING_TARGET_DATE_OFFSET_DAYS` = `0`, `1`, or `2` (manual) or `2` (scheduled)

### Tips

- For a **dry run** in CI: leave **skip_madrid_time_wait** checked and pick **target_date_offset_days** as needed.
- For a **time-accurate rehearsal** (rare on Actions): uncheck **skip_madrid_time_wait**; note the job can still start late relative to 20:00 because of the queue.
- Job **timeout** is 30 minutes (see workflow `timeout-minutes`).

## Target date

- **Default:** `LocalDate.now()` + **`BOOKING_TARGET_DATE_OFFSET_DAYS`** (default **2** days) — used to find the **Google Sheet** row and, when the row has no **Fecha**, to build the inscripción URL date.
- **Override:** set `BOOKING_TARGET_DATE_OFFSET_DAYS` in `.env` or, on GitHub, only for **manual** runs via **target_date_offset_days**.

## Time window (Europe/Madrid)

When **`SKIP_MADRID_TIME_WAIT`** is **not** set (or `false`):

| Step | Time (Madrid, same day as `performBooking` starts) |
|------|------------------------------------------------------|
| Start browser / driver | **~19:59:20** |
| **Login** + **Recorrido** visible | **By ~19:59:50** (deadline) |
| Start form fill (Recorrido, hoyos, jugadores, hora, …) | **~20:00:02** |

If the JVM starts **after** these times, the code does **not** wait backwards; it continues immediately (with “already past deadline” style logs). So for predictable behaviour the process should start **before** ~20:00 Madrid.

## Bypassing the Madrid wait

- **Local:** in `.env` set `SKIP_MADRID_TIME_WAIT=true` (or export it in the shell).
- **GitHub — manual run:** use the **skip_madrid_time_wait** input (default true = bypass).
- **GitHub — scheduled run:** `SKIP_MADRID_TIME_WAIT` is always **`false`**; the app uses the normal Madrid window.

## Optional environment variables

| Variable | Default | Purpose |
|----------|---------|--------|
| `SKIP_MADRID_TIME_WAIT` | unset → false | `true` skips the 19:59:20 / 20:00:02 Madrid waits (process env, then `.env`) |
| `BOOKING_TARGET_DATE_OFFSET_DAYS` | `2` | `0`–`2` — days from today for sheet/URL (process env, then `.env`) |
| `HEADLESS` | `true` | `false` shows Chrome (useful when debugging) |
| `WAIT_AFTER_RECORRIDO_MS` | `1200` | After changing Recorrido, pause before the next control (ms) |
| `DEBUG_WAIT_AFTER_JUGADORES_MS` | `1000` | After **Jugadores**, pause before **Hora de juego** (ms) |
| `EMAIL_DEBUG` | `false` | Jakarta Mail debug |
| `EMAIL_TIMEOUT_MS` | `15000` | SMTP timeouts |

## Flow (summary)

1. Load configuration; resolve **target date** and read the matching **Google Sheet** row.
2. (Unless skipped) **Wait** until the configured **Europe/Madrid** instants, then open the inscripción URL, log in, and wait for **Recorrido**.
3. Fill **Recorrido** (with waits for options/AJAX), **Número de hoyos**, **Jugadores**, **Hora de juego**; then **BLOQUEAR**, add socios, **RESERVAR**.
4. On success or failure, send email; include **Hora de juego** option labels if that step ran.

## Local cron (optional)

To mirror production timing on your own machine (no GitHub queue), run the JAR in **`Europe/Madrid`** a few **minutes before** 20:00, e.g.:

```cron
CRON_TZ=Europe/Madrid
50 19 * * * cd /path/to/java-prat-booking && /usr/bin/java -jar target/teeone-booking-jar-with-dependencies.jar >> logs/cron.log 2>&1
```

The **application** then applies its own 19:59:20 / 20:00:02 waits (without `SKIP_MADRID_TIME_WAIT`).

## Troubleshooting

- **No slots / BLOQUEAR disabled** — the club may show “no availability”; fix date, recorrido, and jugadores on the real site; automation cannot invent tee times.
- **Chrome / headless** — ensure Chrome is installed; set `HEADLESS=false` to watch the browser.
- **Selectors** — if TeeOne’s HTML changes, update `TeeOneAutomation.java`.
- **Sheet tab** — if not `Sheet1!A:Z`, set `GOOGLE_SHEET_RANGE` in secrets / `.env`.
