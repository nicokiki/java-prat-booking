# TeeOne Golf Booking

Automated tee time booking for [TeeOne](https://members.teeone.golf) (Prat). It logs in, fills the inscripción form, and confirms the reserva. Booking preferences come from **Google Sheets**; results are sent by **email**. The app is tuned for **Europe/Madrid** wall-clock behaviour around **20:00** so slots that open on the hour are hit at the right time.

You can run it **locally** (Java + Chrome) or on **GitHub Actions** (externally scheduled or manual).

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

**Optional (see [Optional environment variables](#optional-environment-variables)):** `SKIP_MADRID_TIME_WAIT`, `BOOKING_TARGET_DATE_OFFSET_DAYS`, `HEADLESS`, etc.

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
| **workflow_dispatch** | Run manually via **Actions** → workflow → **Run workflow**, or through the GitHub API from an external scheduler. With **skip_madrid_time_wait=false**, the workflow **waits until 19:35** Madrid before setup, `mvn`, and the JAR; the app waits until **19:59:20 / 20:00:01** for login and form fill. If the runner starts after **19:57** Madrid, the job **fails fast before setup** (no booking attempt) and attempts an email alert using the SMTP secrets. |

The built-in GitHub cron trigger is disabled. Automatic daily runs require an external scheduler to be configured separately; until then, only manual/API dispatches run. Dispatch daily around **19:00 Europe/Madrid** with **skip_madrid_time_wait=false** and **target_date_offset_days=2**. Hosted-runner startup can still be delayed.

### External scheduling with cron-job.org

[cron-job.org](https://cron-job.org/en/faq/) sends one HTTP request each day to dispatch the workflow. It does **not** run Java or hold the HTTP connection open until booking time. GitHub accepts the request, queues a runner, and the workflow/application handle the waits.

#### Daily execution timeline

All times below are **Europe/Madrid**, including daylight-saving changes:

1. **19:00:** cron-job.org sends the dispatch request to GitHub. The HTTP response confirms whether the request was accepted, not whether a booking succeeded.
2. **When the runner starts:** the workflow checks the clock. After **19:57:00**, it fails before checkout/setup and attempts a late-start email alert; no booking is attempted.
3. **Until 19:35:** the runner waits before checkout, Java/Chrome setup, credentials creation, and the Maven build. A runner starting between 19:35 and the cutoff skips this wait and starts setup immediately.
4. **Around 19:59:20:** the Java application starts browser/login preparation, aiming for login and the **Recorrido** field to be ready by **19:59:50**.
5. **Around 20:00:01:** the application starts filling the booking form, attempts the reservation, and sends the result email.

These are intended timings, not a punctuality guarantee. External scheduling avoids GitHub's built-in cron-trigger delays, but cron-job.org, runner allocation, setup/build, and the booking site can still be delayed. The initial cutoff does not guarantee that a slow setup/build finishes in time.

#### Create a restricted GitHub token

In GitHub, open **Settings → Developer settings → Personal access tokens → Fine-grained tokens**:

1. Create a token for the repository owner and select **only `java-prat-booking`**.
2. Grant repository permission **Actions: Read and write**.
3. Set an expiration and a renewal reminder. If owner/organization approval is required, obtain it before testing.

The direct integration requires storing this token in cron-job.org's request settings. Treat it as a secret: never put it in the URL, commit it, or include it in screenshots/logs. Revoke and replace it if exposed, and update the scheduler before it expires. TeeOne, Google Sheets, and SMTP credentials remain in **GitHub Actions secrets**, not in cron-job.org.

#### Configure the cron-job.org job

Create an account and one enabled job with:

- **Schedule:** every day at **19:00**.
- **Timezone:** **Europe/Madrid**; verify the displayed next execution time before enabling the job.
- **HTTP method:** **POST**.
- **URL:**

```text
https://api.github.com/repos/nicokiki/java-prat-booking/actions/workflows/teeone-book.yml/dispatches
```

In the advanced request settings, add these headers, replacing the token placeholder privately:

```text
Authorization: Bearer YOUR_GITHUB_TOKEN
Accept: application/vnd.github+json
Content-Type: application/json
X-GitHub-Api-Version: 2022-11-28
```

Set the request body to:

```json
{
  "ref": "main",
  "inputs": {
    "skip_madrid_time_wait": false,
    "target_date_offset_days": "2"
  }
}
```

Explicitly passing **false** is essential: the workflow input defaults to **true**, which bypasses both the timing checks and waits. The workflow must be present on GitHub's default branch, and `main` must contain the dispatch-enabled version.

Enable scheduler failure notifications. Avoid automatic retries or a second scheduler that can dispatch duplicate runs: a retry after an ambiguous HTTP timeout may create another run even if GitHub accepted the first request. The workflow's **cancel-in-progress** setting cancels an active run when another is dispatched; it does not deduplicate completed bookings.

#### Verify and operate

Testing the HTTP request launches the **real booking workflow**. Test only on an intended booking day, before **19:57 Madrid**, and do not issue another test while a run is active. Neither bypassing the waits nor changing the target date makes a run a dry run.

1. Check cron-job.org's execution history. A successful dispatch normally returns **HTTP 204 No Content**; an empty response is expected.
2. Open **GitHub → Actions → TeeOne booking (scheduled)** and confirm a new run appeared on `main`.
3. Check that the late-start guard passed and the **Wait until pre-build window** step waits until 19:35 (or reports that the time has already passed).
4. Check the eventual GitHub run result and booking email. Scheduler success only confirms dispatch; it does not monitor the later Actions job or reservation.

For a rejected request, check the token's validity, repository selection, Actions permission, workflow filename, branch, and JSON body. If a request times out, inspect GitHub Actions before retrying. For a late-start email, inspect the runner's start time; repeatedly dispatching cannot recover the missed booking window.

For a manual fallback, use **Run workflow**, uncheck **skip_madrid_time_wait**, and select **target_date_offset_days=2** before the cutoff. First confirm no daily run is already active or has completed the booking.

**Activation checklist:** workflow changes pushed to `main`; token created; cron-job.org configured and enabled; next execution time checked; one intended booking run verified. Until the external job is enabled, there are **no automatic daily triggers**. Pause the cron-job.org job to stop future automatic dispatches; pausing it does not cancel a run already queued or running in GitHub.

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
| **skip_madrid_time_wait** | `true` (checked) | If **true**: bypass the 19:35 wait, late-start check, and internal Europe/Madrid waits (see [Time window](#time-window-europemadrid)); the flow runs as soon as the JAR starts. If **false**: enforce the 19:57 cutoff and use the production waits. External schedulers must explicitly pass **false**. |
| **target_date_offset_days** | `2` | `0` = today, `1` = tomorrow, `2` = in two days — for **which sheet row** and, when the sheet’s **Fecha** is empty, the booking URL date. External schedulers should pass **2**. |

The workflow maps these to process environment variables for the JAR:

- `SKIP_MADRID_TIME_WAIT` = `true` or `false` from the dispatch input
- `BOOKING_TARGET_DATE_OFFSET_DAYS` = `0`, `1`, or `2` from the dispatch input

### Tips

- For an **immediate run** in CI: leave **skip_madrid_time_wait** checked and pick **target_date_offset_days** as needed. This is not a dry run: it can make a real booking.
- For a **time-accurate rehearsal** (rare on Actions): uncheck **skip_madrid_time_wait**; note the job can still start late relative to 20:00 because of the queue.
- Job **timeout** is 360 minutes for all dispatches, including the idle wait until 19:35 when enabled.
- For an external scheduler calling `workflow_dispatch`, set **skip_madrid_time_wait** to **false** and **target_date_offset_days** to **2**. External scheduling requires separate setup. Avoid dispatching another run while one is active: the shared concurrency group cancels the previous run, and does not prevent duplicate bookings after a completed run.

## Target date

- **Default:** `LocalDate.now()` + **`BOOKING_TARGET_DATE_OFFSET_DAYS`** (default **2** days) — used to find the **Google Sheet** row and, when the row has no **Fecha**, to build the inscripción URL date.
- **Override:** set `BOOKING_TARGET_DATE_OFFSET_DAYS` in `.env` or, on GitHub, via the **target_date_offset_days** dispatch input.

## Time window (Europe/Madrid)

When **`SKIP_MADRID_TIME_WAIT`** is **not** set (or `false`):

| Step | Time (Madrid, same day as `performBooking` starts) |
|------|------------------------------------------------------|
| Start browser / driver | **~19:59:20** |
| **Login** + **Recorrido** visible | **By ~19:59:50** (deadline) |
| Start form fill (Recorrido, hoyos, jugadores, hora, …) | **~20:00:01** |

If the JVM starts **after** these times, the code does **not** wait backwards; it continues immediately (with “already past deadline” style logs). So for predictable behaviour the process should start **before** ~20:00 Madrid.

## Bypassing the Madrid wait

- **Local:** in `.env` set `SKIP_MADRID_TIME_WAIT=true` (or export it in the shell).
- **GitHub — manual run:** use the **skip_madrid_time_wait** input (default true = bypass).
- **GitHub — externally scheduled run:** pass **skip_madrid_time_wait=false**; the workflow enforces the late-start cutoff and the app uses the normal Madrid window.

## Optional environment variables

| Variable | Default | Purpose |
|----------|---------|--------|
| `SKIP_MADRID_TIME_WAIT` | unset → false | `true` skips the 19:59:20 / 20:00:01 Madrid waits (process env, then `.env`) |
| `BOOKING_TARGET_DATE_OFFSET_DAYS` | `2` | `0`–`2` — days from today for sheet/URL (process env, then `.env`) |
| `HEADLESS` | `true` | `false` shows Chrome (useful when debugging) |
| `WAIT_AFTER_RECORRIDO_MS` | Not used | Legacy setting; readiness waits now replace the fixed inter-dropdown pause |
| `DEBUG_WAIT_AFTER_JUGADORES_MS` | Not used | Legacy setting; Hora de juego now waits for usable options |
| `EMAIL_DEBUG` | `false` | Jakarta Mail debug |
| `EMAIL_TIMEOUT_MS` | `15000` | SMTP timeouts |

## Flow (summary)

1. Load configuration; resolve **target date** and read the matching **Google Sheet** row.
2. (Unless skipped) **Wait** until the configured **Europe/Madrid** instants, then open the inscripción URL, log in, and wait for **Recorrido**.
3. Fill **Recorrido** (with waits for options/AJAX), **Número de hoyos**, **Jugadores**, **Hora de juego**; then **BLOQUEAR**, add socios, **RESERVAR**.
4. On success or failure, send email; include **Hora de juego** option labels if that step ran.

### Form readiness and validation

Each dropdown is re-found every 200 ms until it is visible, enabled, and contains
an enabled matching option. Recorrido allows up to 25 seconds; Número de hoyos
and Jugadores allow 15 seconds. Hora de juego allows 15 seconds for an enabled,
nonblank option other than the `-1` placeholder, then applies the existing time
and player-count preferences. An empty-dropdown timeout is reported separately
from a populated list with no matching time. BLOQUEAR waits up to 15 seconds
to become visible and enabled before a normal Selenium click.

The existing post-Recorrido AJAX wait remains. These checks do not guarantee
that an already-populated dropdown reflects the latest server response, or
that no overlay covers BLOQUEAR. They do not retry or refresh a failed server
availability request.

Run `mvn test` for mock-only regression tests, or
`mvn clean package assembly:single` to also build the production JAR. The
**Validate booking automation** workflow runs this build for PRs and pushes to
main without credentials, email, or a live booking. Future booking dispatches
check out main and rebuild the JAR. To roll back a merged change, use the PR's
**Revert** action and merge the resulting revert PR before the next dispatch.

### Successful booking screenshot

The success email subject uses the format
`TeeOne Booking -- Success -- dd/MM/yyyy @ HH:mm @ Rosa | Booked at hh:mm:ss AM/PM`.
The date is the golf play date (`Fecha`), the tee time is the actual selected
option (including fallback times, without the player-count suffix), and the
course is shortened to Rosa, Verde, or Amarillo. The booking time is always in
`Europe/Madrid`, recorded immediately when the existing success-message check
passes after RESERVAR, before screenshot capture or email delivery. The body
keeps its confirmation time as `hh:mm:ss:SSS AM/PM` with three-digit milliseconds,
plus the confirmation date and UTC offset, including daylight-saving adjustments.
This is when the automation detects confirmation, not the scheduled tee time
or a server-provided transaction timestamp.

After RESERVAR has been clicked and the existing success-message check confirms
the reservation, the automation captures the visible browser viewport as a PNG.
The success email includes it as `booking-confirmation.png`, alongside the
existing booking details and time-option list. No screenshot is taken before
confirmation or for an unsuccessful reservation, so capture cannot delay
BLOQUEAR, adding socios, or RESERVAR.

Capture is best-effort: an empty image or capture exception is logged without
changing booking success, and the email is sent without an attachment. The
image stays in memory until email delivery; no screenshot file or Actions
artifact is created. It shows only what is visible on the confirmation page,
not necessarily every socio or content below the viewport. Treat the email as
private because the screenshot may contain member and reservation details.

## Local cron (optional)

To mirror production timing on your own machine (no GitHub queue), run the JAR in **`Europe/Madrid`** a few **minutes before** 20:00, e.g.:

```cron
CRON_TZ=Europe/Madrid
50 19 * * * cd /path/to/java-prat-booking && /usr/bin/java -jar target/teeone-booking-jar-with-dependencies.jar >> logs/cron.log 2>&1
```

The **application** then applies its own 19:59:20 / 20:00:01 waits (without `SKIP_MADRID_TIME_WAIT`).

## Troubleshooting

- **No slots / BLOQUEAR disabled** — the club may show “no availability”; fix date, recorrido, and jugadores on the real site; automation cannot invent tee times.
- **Chrome / headless** — ensure Chrome is installed; set `HEADLESS=false` to watch the browser.
- **Selectors** — if TeeOne’s HTML changes, update `TeeOneAutomation.java`.
- **Sheet tab** — if not `Sheet1!A:Z`, set `GOOGLE_SHEET_RANGE` in secrets / `.env`.
