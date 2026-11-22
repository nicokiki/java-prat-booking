package com.prat.booking;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser automation for TeeOne golf booking.
 */
public class TeeOneAutomation {

    private static final Logger log = LoggerFactory.getLogger(TeeOneAutomation.class);
    private static final Logger teeTimeHtmlLog = LoggerFactory.getLogger("com.prat.booking.TeeTimeHtml");
    private static final String RENDER_TEE_TIME_SCREENSHOT = """
            const template = document.createElement('template');
            template.innerHTML = arguments[0];
            const source = template.content.querySelector('select');
            if (!source) return null;
            const panel = document.createElement('div');
            panel.style.cssText = 'position:fixed;left:10px;top:10px;z-index:2147483647;width:360px;padding:12px;background:white;color:black;font:16px sans-serif';
            const shadow = panel.attachShadow({mode: 'open'});
            const title = document.createElement('div');
            const metadata = arguments[1];
            title.textContent = metadata.course + ' | ' + metadata.date + ' | ' + metadata.players + ' players | ' + metadata.capturedAt;
            title.style.cssText = 'margin-bottom:8px;font:14px sans-serif';
            const dropdown = document.createElement('select');
            dropdown.style.cssText = 'width:100%;height:auto;font:16px sans-serif';
            dropdown.disabled = source.disabled;
            for (const original of source.options) {
                const option = document.createElement('option');
                option.textContent = original.label || original.textContent;
                option.disabled = original.disabled;
                option.selected = original.selected;
                dropdown.append(option);
            }
            dropdown.size = Math.max(2, Math.min(dropdown.options.length, 20));
            shadow.append(title, dropdown);
            document.body.append(panel);
            return {panel: panel, pages: Math.max(1, Math.ceil(Math.min(dropdown.options.length, 100) / 20))};
            """;
    private static final String BASE_URL = "https://members.teeone.golf/prat/reservas/inscripcion/";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PAGE_LOAD_WAIT = Duration.ofSeconds(3);
    private static final int MAX_TEE_TIME_DISTANCE_MINUTES = 90;
    private static final String INSTALL_AVAILABILITY_OBSERVER = """
            if (!window.jQuery) return false;
            if (window.__pratAvailability) return true;
            const state = {sequence: 0, latest: null};
            const requests = new WeakMap();
            const endpoint = '/api/Front/ReservasSocios/ObtenerHorasDisponiblesReservasSocios';
            function filterKey(course, holes, players, date) {
                if (!['02', '03', '04'].includes(String(course)) || !['1', '2'].includes(String(holes))
                    || !['2', '3', '4'].includes(String(players)) || !/^\\d{4}\\/\\d{2}\\/\\d{2}$/.test(date)) return null;
                return [String(course), String(holes), String(players), date].join('|');
            }
            state.currentKey = function() {
                const value = id => document.getElementById(id)?.value || '';
                const date = value('HidFechaJuego');
                return filterKey(value('Recorrido'), value('Hoyos'), value('Plazas'),
                    /^\\d{8}$/.test(date) ? date.slice(0, 4) + '/' + date.slice(4, 6) + '/' + date.slice(6, 8) : '');
            };
            window.jQuery(document).on('ajaxSend.pratAvailability', function(event, xhr, settings) {
                if (!String(settings.url || '').split('?')[0].endsWith(endpoint)) return;
                try {
                    const input = typeof settings.data === 'string' ? JSON.parse(settings.data) : settings.data;
                    const key = filterKey(input.idRecorrido, input.numHoyos, input.numJugadores, input.fechaJuego);
                    if (!key || key !== state.currentKey()) return;
                    const request = {key: key, requestId: ++state.sequence, pending: true};
                    requests.set(xhr, request);
                    state.latest = request;
                } catch (error) { state.latest = null; }
            });
            window.jQuery(document).on('ajaxComplete.pratAvailability', function(event, xhr) {
                const request = requests.get(xhr);
                if (!request || request.requestId !== state.latest?.requestId) return;
                const response = xhr.responseJSON;
                const numeric = value => typeof value === 'number' && Number.isFinite(value) ? value : null;
                state.latest = {key: request.key, requestId: request.requestId, pending: false,
                    httpStatus: numeric(xhr.status), apiCode: numeric(response?.res?.cod),
                    timeCount: Array.isArray(response?.datos?.lista) ? response.datos.lista.length : null,
                    systemOpen: typeof response?.datos?.sistemaAbierto === 'boolean' ? response.datos.sistemaAbierto : null};
            });
            window.__pratAvailability = state;
            return true;
            """;
    private static final String READ_AVAILABILITY_RESPONSE = """
            const state = window.__pratAvailability;
            return state && state.latest && state.latest.key === state.currentKey() ? state.latest : null;
            """;

    private WebDriver driver;
    private WebDriverWait wait;
    private boolean initialized;

    private Boolean reservationSuccess; // null until RESERVAR attempted
    private String reservationAlertClass;
    private String reservationAlertText;
    /** Visible labels from the Hora de juego dropdown after it is read; used in notification emails. */
    private String horaDeJuegoDropdownOptions;
    private byte[] reservationScreenshot;
    private ZonedDateTime reservationBlockedAt;
    private ZonedDateTime reservationConfirmedAt;
    private String selectedHoraDeJuego;
    private String selectedRecorrido;
    private final List<Map<String, Object>> availabilitySnapshots = new ArrayList<>();
    private Duration diagnosticRecheckDelay = Duration.ofSeconds(15);
    private Duration diagnosticObservationTimeout = Duration.ofSeconds(10);
    private Path teeTimeCaptureDirectory = Path.of("logs", "availability");
    private final Set<String> capturedTeeTimeScreenshots = new HashSet<>();
    private record TeeTimeEvidence(String course, String date, int players, String phase, String capturedAt, String html) {}
    private final List<TeeTimeEvidence> pendingTeeTimeEvidence = new ArrayList<>();
    private final Map<String, String> observedSocioNames = new LinkedHashMap<>();
    private boolean reservationSubmitted;
    private boolean bookingUnavailable;
    private ZonedDateTime bookingFormStartedAt;
    private Map<String, Object> bookingHolder = Map.of();

    public TeeOneAutomation() {
        // Driver is initialized later (time-based) from performBooking()
    }

    public void close() {
        if (driver != null) {
            driver.quit();
            driver = null;
            wait = null;
            initialized = false;
        }
    }

    /**
     * Performs the full booking flow.
     */
    public void performBooking(BookingData data) {
        perform(data, false);
    }

    public void performAvailabilityInspection(LocalDate targetDate) {
        perform(new BookingData(targetDate.format(DateTimeFormatter.BASIC_ISO_DATE), null, null, 2, List.of()), true);
    }

    private boolean availabilityOnly;
    private boolean informationalComplete;

    public boolean isAvailabilityOnly() { return availabilityOnly; }
    public boolean isInformationalComplete() { return informationalComplete; }

    private void perform(BookingData data, boolean inspectOnly) {
        availabilityOnly = inspectOnly;
        informationalComplete = false;
        reservationSuccess = null;
        reservationAlertClass = null;
        reservationAlertText = null;
        horaDeJuegoDropdownOptions = null;
        reservationScreenshot = null;
        reservationBlockedAt = null;
        reservationConfirmedAt = null;
        selectedHoraDeJuego = null;
        selectedRecorrido = null;
        availabilitySnapshots.clear();
        capturedTeeTimeScreenshots.clear();
        pendingTeeTimeEvidence.clear();
        observedSocioNames.clear();
        reservationSubmitted = false;
        bookingUnavailable = false;
        bookingFormStartedAt = null;
        bookingHolder = Map.of();

        ZoneId madrid = ZoneId.of("Europe/Madrid");
        ZonedDateTime nowMadrid = ZonedDateTime.now(madrid);
        ZonedDateTime loginCompleteBy = nowMadrid.toLocalDate().atTime(LocalTime.of(19, 59, 50)).atZone(madrid);
        ZonedDateTime initDriverAt = loginCompleteBy.minusSeconds(30);
        ZonedDateTime fillAt = nowMadrid.toLocalDate().atTime(LocalTime.of(20, 0, 1)).atZone(madrid);
        if (Config.skipMadridTimeWait()) {
            ZonedDateTime past = nowMadrid.minusSeconds(1);
            initDriverAt = past;
            loginCompleteBy = past;
            fillAt = past;
            log.warn("SKIP_MADRID_TIME_WAIT: skipping Madrid clock waits (init/login/fill)");
        }

        log.info("Madrid timing: initDriverAt={}, loginCompleteBy={}, fillAt={}",
                initDriverAt.toLocalTime(), loginCompleteBy.toLocalTime(), fillAt.toLocalTime());


        String dateStr = (data.getFecha() != null && !data.getFecha().isBlank())
                ? data.getFecha().trim()
                : LocalDate.now().plusDays(Config.bookingTargetDateOffsetDays()).format(DateTimeFormatter.BASIC_ISO_DATE);
        String url = BASE_URL + dateStr;

        waitUntilMadrid(initDriverAt, "initDriver");
        initDriverIfNeeded();

        log.info("Navigating to {}", url);
        driver.get(url);
        sleep(PAGE_LOAD_WAIT);

        loginUntilRecorridoVisible(loginCompleteBy);
        installAvailabilityObserver();

        // Start filling exactly at 20:00:01 Madrid time when possible.
        waitUntilMadrid(fillAt, "fillBookingForm");
        if (inspectOnly) {
            inspectAllCourses();
            informationalComplete = true;
            return;
        }
        bookingFormStartedAt = ZonedDateTime.now(madrid);
        try {
            fillBookingForm(data);
        } catch (NoSuitableTeeTimeException unavailable) {
            recheckEmptyAvailability();
            throw unavailable;
        }
        captureBookingHolder();
        addSocios(data);
        confirmReservation();
        sleep(Duration.ofMillis(1500));
    }

    private void initDriverIfNeeded() {
        if (initialized) return;

        ChromeOptions options = new ChromeOptions();
        if (Config.headless()) {
            options.addArguments("--headless=new");
        }
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");

        this.driver = new ChromeDriver(options);
        this.wait = new WebDriverWait(driver, DEFAULT_TIMEOUT);
        this.initialized = true;
    }

    private void waitUntilMadrid(ZonedDateTime targetMadrid, String stepName) {
        ZonedDateTime now = ZonedDateTime.now(targetMadrid.getZone());
        if (now.isBefore(targetMadrid)) {
            Duration d = Duration.between(now, targetMadrid);
            long ms = d.toMillis();
            log.info("Waiting {}ms until {} at {} ({})",
                    ms, stepName, targetMadrid.toLocalTime(), targetMadrid.getZone());
            sleep(Duration.ofMillis(ms));
        } else {
            log.info("Not waiting for {} (now={} >= target={})",
                    stepName, now.toLocalTime(), targetMadrid.toLocalTime());
        }
    }

    private void login() {
        log.info("Logging in...");
        WebElement userInput = wait.until(ExpectedConditions.presenceOfElementLocated(
                By.cssSelector("input[type='text'], input[name='username'], input[id*='user'], input[placeholder*='Usuario']")));
        WebElement passInput = driver.findElement(By.cssSelector(
                "input[type='password'], input[name='password'], input[id*='pass']"));

        userInput.clear();
        userInput.sendKeys(Config.teeOneUsername());
        passInput.clear();
        passInput.sendKeys(Config.teeOnePassword());

        WebElement submitBtn = driver.findElement(By.xpath(
                "//button[@type='submit'] | //input[@type='submit'] | //button[contains(.,'Iniciar')] | //a[contains(@class,'btn')]"));
        submitBtn.click();
    }

    private void loginUntilRecorridoVisible(ZonedDateTime loginCompleteByMadrid) {
        ZonedDateTime now = ZonedDateTime.now(loginCompleteByMadrid.getZone());
        if (now.isAfter(loginCompleteByMadrid)) {
            log.warn("Already past login deadline {} (now={}); will attempt login anyway",
                    loginCompleteByMadrid.toLocalTime(), now.toLocalTime());
        }

        // Trigger login (if we are on login page).
        try {
            login();
        } catch (Exception e) {
            log.info("Login form not found or already logged in; proceeding to wait for Recorrido");
        }

        Duration remaining = Duration.between(ZonedDateTime.now(loginCompleteByMadrid.getZone()), loginCompleteByMadrid);
        Duration waitForRecorrido = (remaining.isNegative() || remaining.isZero()) ? DEFAULT_TIMEOUT : remaining;

        if (remaining.isNegative() || remaining.isZero()) {
            log.info("Deadline already passed; waiting up to {}ms for Recorrido visibility",
                    waitForRecorrido.toMillis());
        } else {
            log.info("Waiting up to {}ms for login to complete (Recorrido visible) before {}",
                    waitForRecorrido.toMillis(), loginCompleteByMadrid.toLocalTime());
        }

        try {
            new WebDriverWait(driver, waitForRecorrido).until(d -> {
                WebElement recorrido = findSelectByLabelOrName("Recorrido");
                return recorrido != null && recorrido.isDisplayed();
            });
            log.info("Login complete: Recorrido dropdown is visible");
        } catch (TimeoutException e) {
            if (remaining.isNegative() || remaining.isZero()) {
                log.warn("Recorrido dropdown still not visible after {}ms; continuing anyway",
                        waitForRecorrido.toMillis());
            } else {
                log.warn("Login did not complete before deadline {} (Recorrido not visible); continuing anyway",
                        loginCompleteByMadrid.toLocalTime());
            }
        }
    }

    private void fillBookingForm(BookingData data) {
        log.info("Filling booking form: Recorrido={}, Horario={}, Socios={}", data.getRecorrido(), data.getHorario(), data.getSocios());

        try {
            selectCourseAndTime(data, data.getRecorrido());
        } catch (NoSuitableTeeTimeException unavailable) {
            String alternate = alternateRecorrido(data.getRecorrido());
            if (alternate == null) {
                bookingUnavailable = true;
                throw unavailable;
            }
                log.warn("No suitable time on '{}'; trying '{}' within {} minutes of '{}'",
                    data.getRecorrido(), alternate, MAX_TEE_TIME_DISTANCE_MINUTES, data.getHorario());
            try {
                selectCourseAndTime(data, alternate);
            } catch (NoSuitableTeeTimeException alternateUnavailable) {
                bookingUnavailable = true;
                alternateUnavailable.addSuppressed(unavailable);
                throw alternateUnavailable;
            }
        }

        log.info("Already selected recorrido: {}, players: {} and horario: {} ... About to click on bloquear button",
                selectedRecorrido, data.getSocios(), selectedHoraDeJuego);

        By bloquearLocator = By.xpath(
            "//button[@id='BtnBloquear'] | " +
            "//button[.//span[normalize-space()='BLOQUEAR']] | " +
            "//button[contains(.,'BLOQUEAR')]");
        WebElement bloquearBtn = new WebDriverWait(driver, DEFAULT_TIMEOUT)
            .pollingEvery(Duration.ofMillis(200))
            .until(ExpectedConditions.elementToBeClickable(bloquearLocator));
        String bloquearId = bloquearBtn.getAttribute("id");
        String bloquearClass = bloquearBtn.getAttribute("class");

        ZonedDateTime blockedAt = ZonedDateTime.now(ZoneId.of("Europe/Madrid"));
        bloquearBtn.click();
        reservationBlockedAt = blockedAt;
        log.info("Clicked on bloquear button successfully id={} class={}",
            bloquearId, bloquearClass);
        sleep(Duration.ofSeconds(2)); // Page reloads after BLOQUEAR
    }

    private void selectCourseAndTime(BookingData data, String recorrido) {
        selectCourseFilters(recorrido, data.getSocios());
        selectHoraDeJuego(data.getHorario(), data.getSocios());
    }

    private void selectCourseFilters(String recorrido, int players) {
        selectedHoraDeJuego = null;
        horaDeJuegoDropdownOptions = null;
        installAvailabilityObserver();
        try {
            selectDropdown("Recorrido", recorrido);
            waitUntilJQueryAjaxIdle(DEFAULT_TIMEOUT, true);
            selectDropdown("Número de hoyos", "18");
            waitUntilJQueryAjaxIdle(DEFAULT_TIMEOUT, true);
            selectDropdown("Jugadores", String.valueOf(players));
            waitUntilJQueryAjaxIdle(DEFAULT_TIMEOUT, true);
        } catch (RuntimeException exception) {
            logTeeTimeDiagnostics(players, "filter-failure");
            throw exception;
        }
    }

    private void installAvailabilityObserver() {
        try {
            ((JavascriptExecutor) driver).executeScript(INSTALL_AVAILABILITY_OBSERVER);
        } catch (RuntimeException exception) {
            log.warn("Availability response observer unavailable; empty results will require confirmed response evidence");
        }
    }

    private void inspectAllCourses() {
        WebElement courseDropdown = waitUntilDropdownReady("Recorrido", option ->
            !optionVisibleText(option).isBlank() && !"-1".equals(option.getAttribute("value")), "available course options");
        boolean verdeOffered = new Select(courseDropdown).getOptions().stream().anyMatch(option ->
                optionMatchesChoice(option, "TEE 1 VERDE", canonicalOptionLabel("TEE 1 VERDE"), compactOptionKey("TEE 1 VERDE")));
        IllegalStateException failure = null;
        for (String course : List.of("TEE 1 ROSA", "TEE 1 AMARILLO", "TEE 1 VERDE")) {
            if ("TEE 1 VERDE".equals(course) && !verdeOffered) {
                log.info("Verde is not offered for this date; skipping");
                continue;
            }
            try {
                selectCourseFilters(course, 2);
                new WebDriverWait(driver, DEFAULT_TIMEOUT).until(current -> Boolean.TRUE.equals(
                        ((JavascriptExecutor) current).executeScript("return !window.jQuery || window.jQuery.active === 0;")));
                WebElement dropdown;
                try {
                    dropdown = waitUntilDropdownReady("Hora de juego", option ->
                            !optionVisibleText(option).isBlank() && !"-1".equals(option.getAttribute("value")),
                            "a nonblank, non-placeholder time option");
                } catch (IllegalStateException exception) {
                    logTeeTimeDiagnostics(2, "readiness-failure");
                    dropdown = findSelectByLabelOrName("Hora de juego");
                    if (!(exception.getCause() instanceof TimeoutException) || dropdown == null
                            || !dropdown.isDisplayed() || !dropdown.isEnabled()) throw exception;
                    requireSuccessfulEmptyAvailability();
                }
                List<WebElement> options = new Select(dropdown).getOptions();
                checkObservedAvailabilityResponse(options, 2);
                captureAvailability(options, 2);
            } catch (RuntimeException exception) {
                if (failure == null) failure = new IllegalStateException("Availability inspection incomplete");
                failure.addSuppressed(exception);
                log.warn("Could not inspect availability for {}", course);
            }
        }
        recheckEmptyAvailability();
        if (failure != null) throw failure;
    }

    private void recheckEmptyAvailability() {
        if (driver == null || reservationSubmitted || reservationBlockedAt != null || selectedHoraDeJuego != null) return;
        Map<String, Integer> emptyCourses = new LinkedHashMap<>();
        for (Map<String, Object> snapshot : availabilitySnapshots) {
            if (snapshot.get("options") instanceof List<?> options && options.isEmpty()
                    && snapshot.get("course") instanceof String course && !course.isBlank()
                    && snapshot.get("players") instanceof Integer players) {
                emptyCourses.putIfAbsent(course, players);
            }
        }
        if (emptyCourses.isEmpty()) return;
        String originalCourse = selectedRecorrido;
        String originalTime = selectedHoraDeJuego;
        String originalOptions = horaDeJuegoDropdownOptions;
        try {
            log.info("Empty tee-time observations: waiting {} seconds for diagnostic-only rechecks; booking will not be retried",
                    diagnosticRecheckDelay.toSeconds());
            sleep(diagnosticRecheckDelay);
            for (Map.Entry<String, Integer> course : emptyCourses.entrySet()) {
                try {
                    selectCourseFilters(course.getKey(), course.getValue());
                    awaitDiagnosticTeeTimes();
                    logTeeTimeDiagnostics(course.getValue(), "delayed-read-only");
                } catch (RuntimeException exception) {
                    log.warn("Diagnostic tee-time recheck failed for {}; original outcome and snapshots retained", course.getKey());
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Diagnostic tee-time rechecks interrupted; original outcome and snapshots retained");
        } finally {
            selectedRecorrido = originalCourse;
            selectedHoraDeJuego = originalTime;
            horaDeJuegoDropdownOptions = originalOptions;
        }
    }

    private void awaitDiagnosticTeeTimes() {
        try {
            new WebDriverWait(driver, diagnosticObservationTimeout)
                    .pollingEvery(Duration.ofMillis(200))
                    .ignoring(StaleElementReferenceException.class)
                    .until(current -> {
                        WebElement dropdown = findSelectByLabelOrName("Hora de juego");
                        if (dropdown == null || !dropdown.isDisplayed() || !dropdown.isEnabled()) return false;
                        return new Select(dropdown).getOptions().stream().anyMatch(option -> {
                            String text = diagnosticTimeLabel(optionVisibleText(option));
                            return !"-1".equals(option.getAttribute("value"))
                                    && !"blank".equals(text) && !"non-time".equals(text);
                        });
                    });
        } catch (TimeoutException exception) {
            log.info("Diagnostic tee-time wait ended after {} seconds without recognizable times on {}",
                    diagnosticObservationTimeout.toSeconds(), selectedRecorrido);
        }
    }

    private void logTeeTimeDiagnostics(int players, String phase) {
        try {
            WebElement dropdown = findSelectByLabelOrName("Hora de juego");
            if (dropdown == null) {
                log.warn("Tee-time diagnostic: phase={} course={} dropdown missing", phase, selectedRecorrido);
                return;
            }
            captureTeeTimeEvidence(dropdown, players, phase);
            Map<String, Object> diagnostic = teeTimeOptionDiagnostics(new Select(dropdown).getOptions());
                diagnostic.put("dropdownHtml", "<select id=\"HoraJuego\""
                    + (dropdown.isEnabled() ? "" : " disabled") + ">"
                    + diagnostic.remove("optionHtml") + "</select>");
            diagnostic.put("phase", phase);
            diagnostic.put("capturedAt", ZonedDateTime.now(ZoneId.of("Europe/Madrid"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            diagnostic.put("course", selectedRecorrido);
            diagnostic.put("expectedPlayers", players);
            diagnostic.put("displayed", dropdown.isDisplayed());
            diagnostic.put("enabled", dropdown.isEnabled());
            Map<String, String> filters = new LinkedHashMap<>();
            for (String filter : List.of("Recorrido", "Número de hoyos", "Jugadores")) {
                WebElement element = findSelectByLabelOrName(filter);
                String label = element == null ? "" : optionVisibleText(new Select(element).getFirstSelectedOption());
                filters.put(filter, label.matches("(?i)TEE ?1 (ROSA|AMARILLO|VERDE)|18|9|[1-4]") ? label : "unexpected-or-missing");
            }
            diagnostic.put("selectedFilters", filters);
            Object active = ((JavascriptExecutor) driver).executeScript("return window.jQuery ? window.jQuery.active : null;");
            diagnostic.put("jqueryActive", active == null ? "not-present" : active instanceof Number ? active : "unavailable");
            diagnostic.put("availabilityResponse", availabilityResponse());
            log.info("Tee-time diagnostic: {}", diagnostic);
        } catch (RuntimeException exception) {
            log.warn("Tee-time diagnostic unavailable: phase={} course={}; original outcome retained", phase, selectedRecorrido);
        }
    }

    private void captureTeeTimeEvidence(WebElement dropdown, int players, String phase) {
        String playDate = "unknown";
        try {
            String url = driver.getCurrentUrl();
            Matcher date = Pattern.compile("/inscripcion/(\\d{8})(?:[/?#]|$)").matcher(url == null ? "" : url);
            if (date.find()) playDate = date.group(1);
            String html = dropdown.getDomProperty("outerHTML");
            if (html != null) pendingTeeTimeEvidence.add(new TeeTimeEvidence(selectedRecorrido, playDate, players, phase,
                    ZonedDateTime.now(ZoneId.of("Europe/Madrid")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), html));
        } catch (RuntimeException exception) {
            log.warn("Raw tee-time HTML unavailable: phase={} course={}; original outcome retained", phase, selectedRecorrido);
        }
    }

    public void saveTeeTimeEvidence() {
        List<TeeTimeEvidence> pending = new ArrayList<>(pendingTeeTimeEvidence);
        pendingTeeTimeEvidence.clear();
        for (TeeTimeEvidence evidence : pending) {
            try {
                teeTimeHtmlLog.info("Tee-time raw HTML: capturedAt={} phase={} course={} date={} players={} html={}",
                        evidence.capturedAt(), evidence.phase(), evidence.course(), evidence.date(), evidence.players(), evidence.html());
                saveTeeTimeScreenshots(evidence);
            } catch (RuntimeException exception) {
                log.warn("Deferred tee-time evidence could not be saved for {}; original outcome retained", evidence.course());
            }
        }
    }

    private void saveTeeTimeScreenshots(TeeTimeEvidence evidence) {
        String stage = "delayed-read-only".equals(evidence.phase()) ? "delayed" : "initial";
        String key = evidence.course() + "|" + stage;
        if (driver == null || capturedTeeTimeScreenshots.contains(key)) return;
        WebElement panel = null;
        List<byte[]> screenshots = new ArrayList<>();
        try {
            Object rendered = ((JavascriptExecutor) driver).executeScript(RENDER_TEE_TIME_SCREENSHOT, evidence.html(),
                    Map.of("course", String.valueOf(evidence.course()), "date", evidence.date(), "players", evidence.players(), "capturedAt", evidence.capturedAt()));
            if (!(rendered instanceof Map<?, ?> state) || !(state.get("panel") instanceof WebElement element)) return;
            panel = element;
            int pages = state.get("pages") instanceof Number count
                    ? Math.max(1, Math.min(count.intValue(), 5)) : 1;
            for (int page = 0; page < pages; page++) {
                ((JavascriptExecutor) driver).executeScript("const dropdown = arguments[0].shadowRoot.querySelector('select'); dropdown.scrollTop = arguments[1] * dropdown.clientHeight;", panel, page);
                byte[] png = panel.getScreenshotAs(OutputType.BYTES);
                if (png == null || png.length == 0) return;
                screenshots.add(png);
            }
        } catch (RuntimeException exception) {
            log.warn("Deferred tee-time screenshot unavailable for {}; original outcome retained", evidence.course());
            return;
        } finally {
            if (panel != null) {
                try {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].remove();", panel);
                } catch (RuntimeException exception) {
                    log.warn("Could not remove deferred tee-time screenshot panel for {}", evidence.course());
                }
            }
        }
        String course = "unknown";
        if (evidence.course() != null) {
            Matcher color = Pattern.compile("\\b(ROSA|AMARILLO|VERDE)\\b").matcher(evidence.course());
            if (color.find()) course = color.group(1).toLowerCase(Locale.ROOT);
        }
        try {
            Files.createDirectories(teeTimeCaptureDirectory);
                for (int index = 0; index < screenshots.size(); index++) {
                Path screenshot = Files.createTempFile(teeTimeCaptureDirectory,
                    evidence.date() + "-" + course + "-p" + evidence.players() + "-" + stage + "-part" + (index + 1) + "-", ".png");
                Files.write(screenshot, screenshots.get(index));
                log.info("Tee-time screenshot: phase={} course={} players={} expanded-dropdown={}",
                    evidence.phase(), evidence.course(), evidence.players(), screenshot);
                }
            capturedTeeTimeScreenshots.add(key);
        } catch (IOException exception) {
            log.warn("Could not save deferred tee-time screenshot for {}; original outcome retained", evidence.course());
        }
    }

    private Map<?, ?> availabilityResponse() {
        try {
            Object observed = ((JavascriptExecutor) driver).executeScript(READ_AVAILABILITY_RESPONSE);
            if (observed instanceof Map<?, ?> response) return response;
        } catch (RuntimeException exception) {
            log.warn("Availability response evidence could not be read");
        }
        return Map.of();
    }

    private boolean successfulAvailabilityResponse(Map<?, ?> response) {
        return Boolean.FALSE.equals(response.get("pending"))
                && response.get("httpStatus") instanceof Number http && http.intValue() >= 200 && http.intValue() < 300
                && response.get("apiCode") instanceof Number api && api.intValue() == 1
                && Boolean.TRUE.equals(response.get("systemOpen"))
                && response.get("timeCount") instanceof Number count && count.intValue() >= 0;
    }

    private void requireSuccessfulEmptyAvailability() {
        Map<?, ?> response = availabilityResponse();
        log.info("Availability response for empty dropdown on {}: {}", selectedRecorrido, response);
        if (!successfulAvailabilityResponse(response)
                || ((Number) response.get("timeCount")).intValue() != 0) {
            throw new IllegalStateException("Empty tee-time dropdown without a matching successful open-system response with zero times");
        }
    }

    private void checkObservedAvailabilityResponse(List<WebElement> options, int players) {
        logTeeTimeDiagnostics(players, "initial-read");
        Map<?, ?> response = availabilityResponse();
        log.info("Availability response on {}: {}", selectedRecorrido, response);
        boolean listed = options.stream().anyMatch(option -> {
            String label = diagnosticTimeLabel(optionVisibleText(option));
            return !"-1".equals(option.getAttribute("value")) && !"blank".equals(label) && !"non-time".equals(label);
        });
        if (!response.isEmpty() && (!successfulAvailabilityResponse(response)
                || (((Number) response.get("timeCount")).intValue() > 0) != listed)) {
            throw new IllegalStateException("Availability request is pending, failed, malformed, or the booking system is closed");
        }
        if (!listed) requireSuccessfulEmptyAvailability();
    }

    private String alternateRecorrido(String recorrido) {
        Matcher color = Pattern.compile("\\b(ROSA|AMARILLO)\\b", Pattern.CASE_INSENSITIVE)
                .matcher(recorrido);
        if (!color.find()) {
            return null;
        }
        return color.replaceFirst("ROSA".equalsIgnoreCase(color.group(1)) ? "AMARILLO" : "ROSA");
    }

    private void selectDropdown(String labelOrName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Empty value for dropdown: " + labelOrName);
        }
        String rawWant = stripInvisibleChars(value.trim());
        String wantCanon = canonicalOptionLabel(rawWant);
        String wantCompact = compactOptionKey(rawWant);

        WebElement selectEl = waitUntilDropdownReady(labelOrName,
            option -> optionMatchesChoice(option, rawWant, wantCanon, wantCompact),
            "an option matching '" + rawWant + "'");

        Select select = new Select(selectEl);
        List<WebElement> options = select.getOptions();
        log.info("Dropdown '{}' options (attempting to select '{}', canonical='{}'):", labelOrName, rawWant, wantCanon);
        for (WebElement opt : options) {
            log.info("  - text: '{}' | value: '{}'", optionVisibleText(opt), opt.getAttribute("value"));
        }

        int matchIndex = -1;
        for (int i = 0; i < options.size(); i++) {
            WebElement opt = options.get(i);
            if (optionMatchesChoice(opt, rawWant, wantCanon, wantCompact)) {
                matchIndex = i;
                break;
            }
        }
        if (matchIndex < 0) {
            String selectId = selectEl.getAttribute("id");
            throw new NoSuchElementException(String.format(
                    "Cannot find option matching '%s' in dropdown '%s' (select id=%s). "
                            + "Compared canonical NFKC labels and compact (no-space) keys; no value match either.",
                    rawWant, labelOrName, selectId == null ? "(none)" : selectId));
        }
        select.selectByIndex(matchIndex);
        sleep(Duration.ofMillis(300));
        if ("Recorrido".equalsIgnoreCase(labelOrName.trim())) {
            waitUntilJQueryAjaxIdle(Duration.ofSeconds(15));
            selectEl = driver.findElement(By.id("Recorrido"));
            select = new Select(selectEl);
        }
        WebElement selected = select.getFirstSelectedOption();
        if ("Recorrido".equalsIgnoreCase(labelOrName.trim())) {
            selectedRecorrido = selected.getText();
        }
        log.info("Dropdown '{}' selected: text='{}' value='{}'", labelOrName, selected.getText(), selected.getAttribute("value"));
    }

    private WebElement waitUntilDropdownReady(String label, Predicate<WebElement> optionReady,
                                              String expectedOption) {
        Duration timeout = DEFAULT_TIMEOUT;
        if ("Recorrido".equalsIgnoreCase(label.trim())) {
            timeout = Duration.ofSeconds(25);
        } else if ("Hora de juego".equalsIgnoreCase(label.trim())) {
            timeout = Duration.ofSeconds(3);
        }
        log.info("Waiting for dropdown '{}' to be enabled and contain {}...", label, expectedOption);
        java.util.function.Function<WebDriver, WebElement> ready = current -> {
            WebElement dropdown = findSelectByLabelOrName(label);
            if (dropdown == null) {
                dropdown = current.findElement(By.xpath(
                        "//label[contains(.,'" + label + "')]/following::select[1] | " +
                        "//*[contains(text(),'" + label + "')]/following::select[1]"));
            }
            if (!dropdown.isDisplayed() || !dropdown.isEnabled()) return null;
            for (WebElement option : new Select(dropdown).getOptions()) {
                if (option.isEnabled() && optionReady.test(option)) return dropdown;
            }
            return null;
        };
        try {
            return new WebDriverWait(driver, timeout)
                    .pollingEvery(Duration.ofMillis(200))
                    .ignoring(StaleElementReferenceException.class)
                    .until(ready);
        } catch (TimeoutException e) {
            if ("Hora de juego".equalsIgnoreCase(label.trim())) {
                Map<?, ?> pending = availabilityResponse();
                if (Boolean.TRUE.equals(pending.get("pending")) && pending.get("requestId") instanceof Number) {
                    timeout = timeout.plusSeconds(1);
                    log.info("Hora de juego: matching availability request {} still pending after 3 seconds; allowing at most 1 extra second",
                            pending.get("requestId"));
                    try {
                        Map<?, ?> completed = new WebDriverWait(driver, Duration.ofSeconds(1))
                                .pollingEvery(Duration.ofMillis(100))
                                .until(current -> {
                                    Map<?, ?> response = availabilityResponse();
                                    if (!java.util.Objects.equals(pending.get("requestId"), response.get("requestId"))) {
                                        throw new IllegalStateException("Availability request changed during the conditional one-second wait");
                                    }
                                    return Boolean.TRUE.equals(response.get("pending")) ? null : response;
                                });
                        log.info("Hora de juego: conditional wait completed with response {}", completed);
                        if (!successfulAvailabilityResponse(completed)) {
                            throw new IllegalStateException("Availability request failed, was malformed, or system closed during the conditional wait");
                        }
                        if (((Number) completed.get("timeCount")).intValue() > 0) {
                            WebElement dropdown = ready.apply(driver);
                            if (dropdown != null) return dropdown;
                            throw new IllegalStateException("Availability response completed with times but dropdown is not ready");
                        }
                    } catch (TimeoutException extensionTimeout) {
                        log.info("Hora de juego: availability request remains pending after the one-second extension");
                        throw new IllegalStateException("Availability request remains pending after the one-second extension");
                    }
                }
            }
            throw new IllegalStateException(
                    "Timed out after " + timeout.toSeconds() + " seconds waiting for dropdown '"
                        + label + "' to be enabled and contain " + expectedOption, e);
        }
    }

    /** Best-effort wait for in-flight TeeOne / jQuery AJAX after changing filters. */
    private void waitUntilJQueryAjaxIdle(Duration maxWait) {
        waitUntilJQueryAjaxIdle(maxWait, false);
    }

    private void waitUntilJQueryAjaxIdle(Duration maxWait, boolean required) {
        try {
            new WebDriverWait(driver, maxWait).pollingEvery(Duration.ofMillis(200))
                    .until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript(
                            "try { return !window.jQuery || window.jQuery.active === 0; } catch (e) { return true; }")));
            log.info("jQuery AJAX idle after filter change");
        } catch (TimeoutException e) {
            if (required) throw new IllegalStateException("Availability filter request did not finish; refusing to change another filter or select a time", e);
            log.warn("jQuery.active did not return to 0 within {} — continuing", maxWait);
        }
    }

    private boolean optionMatchesChoice(WebElement opt, String rawWant, String wantCanon, String wantCompact) {
        String optRaw = optionVisibleText(opt);
        String textCanon = canonicalOptionLabel(optRaw);
        String textCompact = compactOptionKey(optRaw);
        String val = opt.getAttribute("value");
        boolean byValue = val != null && rawWant.equals(val.trim());
        boolean byCanon = !wantCanon.isEmpty() && wantCanon.equals(textCanon);
        boolean byCompact = wantCompact.length() >= 4 && wantCompact.equals(textCompact);
        return byValue || byCanon || byCompact;
    }

    private void selectHoraDeJuego(String sheetHorario, int socios) {
        String raw = sheetHorario == null ? "" : sheetHorario.trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("Horario from sheet is empty");
        }

        WebElement selectEl;
        try {
            selectEl = waitUntilDropdownReady("Hora de juego",
                    option -> !optionVisibleText(option).isBlank()
                            && !"-1".equals(option.getAttribute("value")),
                    "a nonblank, non-placeholder time option");
        } catch (IllegalStateException e) {
            logTeeTimeDiagnostics(socios, "readiness-failure");
            WebElement dropdown = findSelectByLabelOrName("Hora de juego");
            if (e.getCause() instanceof TimeoutException && dropdown != null
                    && dropdown.isDisplayed() && dropdown.isEnabled()) {
                List<WebElement> options = new Select(dropdown).getOptions();
                checkObservedAvailabilityResponse(options, socios);
                captureAvailabilityBestEffort(options, socios);
            }
            if (e.getCause() instanceof TimeoutException && dropdown != null
                    && dropdown.isDisplayed() && dropdown.isEnabled()
                    && new Select(dropdown).getOptions().stream().allMatch(option ->
                        !option.isEnabled() || optionVisibleText(option).isBlank()
                            || "-1".equals(option.getAttribute("value")))) {
                throw new NoSuitableTeeTimeException(e.getMessage(), e);
            }
            throw e;
        }

        Select select = new Select(selectEl);
        List<WebElement> options = select.getOptions();
        checkObservedAvailabilityResponse(options, socios);
        captureAvailabilityBestEffort(options, socios);
        horaDeJuegoDropdownOptions = formatHoraDeJuegoOptionsForEmail(options);
        log.info("Hora de juego options (sheet='{}', socios={}):", raw, socios);
        for (WebElement opt : options) {
            log.info("  - text: '{}' | value: '{}'", optionVisibleText(opt), opt.getAttribute("value"));
        }

        String best = pickBestHoraOptionText(raw, socios, options);
        log.info("Selecting Hora de juego best match: '{}'", best);
        select.selectByVisibleText(best);

        WebElement selected = select.getFirstSelectedOption();
        selectedHoraDeJuego = optionVisibleText(selected).replaceFirst("\\s*\\(\\d+\\)\\s*$", "").trim();
        log.info("Hora de juego selected: text='{}' value='{}'", optionVisibleText(selected), selected.getAttribute("value"));
    }

    private String pickBestHoraOptionText(String sheetHorario, int socios, List<WebElement> options) {
        ParsedHorario parsed = ParsedHorario.parse(sheetHorario);
        if (parsed == null) {
            // fallback: exact match by text if we can't parse
            for (WebElement opt : options) {
                if (opt.isEnabled() && !"-1".equals(opt.getAttribute("value"))
                        && normalize(optionVisibleText(opt)).equalsIgnoreCase(normalize(sheetHorario))) {
                    return optionVisibleText(opt);
                }
            }
            throw new NoSuchElementException("Could not parse Horario and no exact match found: " + sheetHorario);
        }

        List<Integer> preferredNs = allowedSuffixesForSocios(socios);
        Pattern timeLabel = Pattern.compile("^(\\d{1,2}:\\d{2})(?: \\((\\d+)\\))?$");
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        int bestMinutes = Integer.MAX_VALUE;
        int bestPreference = Integer.MAX_VALUE;
        for (WebElement opt : options) {
            if (!opt.isEnabled() || "-1".equals(opt.getAttribute("value"))) {
                continue;
            }
            String original = optionVisibleText(opt);
            Matcher label = timeLabel.matcher(normalize(original));
            if (!label.matches()) {
                continue;
            }
            ParsedHorario offered = ParsedHorario.parse(label.group(1));
            if (offered == null) {
                continue;
            }
            int preference = 0;
            if (label.group(2) != null) {
                String capacity = label.group(2);
                preference = -1;
                for (int index = 0; index < preferredNs.size(); index++) {
                    if (String.valueOf(preferredNs.get(index)).equals(capacity)) {
                        preference = index + 1;
                        break;
                    }
                }
                if (preference < 0) {
                    continue;
                }
            }
            int distance = Math.abs(offered.minutes - parsed.minutes);
            if (distance > MAX_TEE_TIME_DISTANCE_MINUTES) {
                continue;
            }
            if (distance < bestDistance
                    || (distance == bestDistance && offered.minutes < bestMinutes)
                    || (distance == bestDistance && offered.minutes == bestMinutes
                            && preference < bestPreference)) {
                best = original;
                bestDistance = distance;
                bestMinutes = offered.minutes;
                bestPreference = preference;
            }
        }
        if (best != null) {
            return best;
        }

        throw new NoSuitableTeeTimeException(
                "No Hora de juego option found close to '" + sheetHorario + "' within +/-"
                    + MAX_TEE_TIME_DISTANCE_MINUTES + " minutes", null);
    }

    private static class NoSuitableTeeTimeException extends NoSuchElementException {
        NoSuitableTeeTimeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private List<Integer> allowedSuffixesForSocios(int socios) {
        // Rules:
        // socios=2 -> allow (4),(3),(2)
        // socios=3 -> allow (4),(3)
        // socios=4 -> allow (4)
        if (socios >= 4) return List.of(4);
        if (socios == 3) return List.of(4, 3);
        return List.of(4, 3, 2);
    }

    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** BOM / ZWSP etc. often appear in Google Sheets paste; they break exact option matching. */
    private static String stripInvisibleChars(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replaceAll("[\uFEFF\u200B-\u200D\u2060]", "");
    }

    /**
     * Visible text for an {@code <option>}: {@link WebElement#getText()} is usually right; fall back to
     * attributes or JS when Kendo / headless Chrome returns blank.
     */
    private String optionVisibleText(WebElement option) {
        if (option == null) {
            return "";
        }
        try {
            String t = option.getText();
            if (t != null && !t.isBlank()) {
                return t;
            }
        } catch (Exception ignored) {
            // fall through
        }
        String inner = option.getAttribute("innerText");
        if (inner != null && !inner.isBlank()) {
            return inner;
        }
        String tc = option.getAttribute("textContent");
        if (tc != null && !tc.isBlank()) {
            return tc;
        }
        if (driver instanceof JavascriptExecutor) {
            try {
                Object o = ((JavascriptExecutor) driver).executeScript(
                        "var o=arguments[0]; return (o.text||'').trim() || (o.label||'').trim() || (o.innerText||'').trim() || '';",
                        option);
                if (o != null) {
                    String s = String.valueOf(o).trim();
                    if (!s.isEmpty()) {
                        return s;
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        return "";
    }

    /**
     * Unicode NFKC + whitespace collapse + lower case — aligns sheet text with DOM for Latin confusables,
     * fullwidth digits, compatibility characters, etc.
     */
    private static String canonicalOptionLabel(String s) {
        if (s == null) {
            return "";
        }
        String x = stripInvisibleChars(s.trim());
        x = Normalizer.normalize(x, Normalizer.Form.NFKC);
        x = x.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return x;
    }

    /** Ignores all whitespace so "TEE 1" and "TEE1" still match. */
    private static String compactOptionKey(String s) {
        return canonicalOptionLabel(s).replace(" ", "");
    }

    /** Stable {@code id} on TeeOne inscripción form when label text ≠ element id. */
    private static List<String> teeOneSelectIdAlternates(String labelTrimmed) {
        if ("Número de hoyos".equalsIgnoreCase(labelTrimmed)) {
            return List.of("Hoyos");
        }
        if ("Jugadores".equalsIgnoreCase(labelTrimmed)) {
            return List.of("Plazas");
        }
        if ("Hora de juego".equalsIgnoreCase(labelTrimmed)) {
            return List.of("HoraJuego");
        }
        return List.of();
    }

    private static class ParsedHorario {
        final int minutes;

        private ParsedHorario(int minutes) {
            this.minutes = minutes;
        }

        static ParsedHorario parse(String raw) {
            String s = raw == null ? "" : raw.trim();
            // Sheet format: HH:mm (no suffix)
            Pattern p = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})\\s*$");
            Matcher m = p.matcher(s);
            if (!m.matches()) return null;
            int hh = Integer.parseInt(m.group(1));
            int mm = Integer.parseInt(m.group(2));
            if (hh < 0 || hh > 23 || mm < 0 || mm > 59) return null;
            return new ParsedHorario(hh * 60 + mm);
        }
    }

    private WebElement findSelectByLabelOrName(String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String key = label.trim();
        try {
            // TeeOne uses <select id="Recorrido"> etc. — prefer stable id before fuzzy label XPath.
            try {
                WebElement byId = driver.findElement(By.id(key));
                if ("select".equalsIgnoreCase(byId.getTagName())) {
                    return byId;
                }
            } catch (NoSuchElementException ignored) {
                // continue
            }
            for (String altId : teeOneSelectIdAlternates(key)) {
                try {
                    WebElement byId = driver.findElement(By.id(altId));
                    if ("select".equalsIgnoreCase(byId.getTagName())) {
                        return byId;
                    }
                } catch (NoSuchElementException ignored) {
                    // continue
                }
            }

            // Try by label text (label for= or adjacent)
            List<WebElement> labels = driver.findElements(By.xpath(
                    "//label[contains(.,'" + label + "')] | //*[contains(text(),'" + label + "')]"));
            for (WebElement lbl : labels) {
                String forId = lbl.getAttribute("for");
                if (forId != null) {
                    WebElement sel = driver.findElement(By.id(forId));
                    if ("select".equalsIgnoreCase(sel.getTagName())) return sel;
                }
                try {
                    WebElement sel = lbl.findElement(By.xpath("./following-sibling::select | ./parent::*//select"));
                    if (sel != null) return sel;
                } catch (NoSuchElementException ignored) {}
            }
            // Try by name
            String nameHint = label.toLowerCase().replace(" ", "_").replace("ó", "o");
            List<WebElement> selects = driver.findElements(By.cssSelector("select"));
            for (WebElement sel : selects) {
                String name = sel.getAttribute("name");
                if (name != null && name.toLowerCase().contains(nameHint)) return sel;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private void addSocios(BookingData data) {
        List<String> sociosToAdd = data.getSociosToAdd();
        if (sociosToAdd.isEmpty()) {
            log.info("No additional socios to add");
            return;
        }

        for (String socioName : sociosToAdd) {
            String key = socioName == null ? "" : socioName.trim();
            if (key.isEmpty()) {
                throw new IllegalStateException("Socio licence from sheet is empty");
            }
            log.info("Adding socio: {} to the booking form", socioName);
            // Text is inside a nested <span>, so avoid contains(text(),...) and prefer the stable container id.
            WebElement addBtn = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                    "//div[@id='AmigosSocios']//button | " +
                    "//button[.//span[contains(normalize-space(),'Añadir a otro socio')]] | " +
                    "//button[contains(.,'Añadir a otro socio')]"
            )));
            clickWithScrollAndJsFallback(addBtn, "Añadir a otro socio");

            // Popup HTML:
            // <div id="popup-amigo"> ... <div id="table-amigo"><table> <tr data-licencia="..." data-nombre="..."> ... </tr>
            // Clicking the <tr> triggers addAmigoModal(this)
                new WebDriverWait(driver, Duration.ofSeconds(5))
                    .pollingEvery(Duration.ofMillis(200))
                    .ignoring(StaleElementReferenceException.class)
                    .until(ExpectedConditions.visibilityOfElementLocated(By.id("popup-amigo")));

            log.info("Popup-amigo is visible ... about to find the socio row ... looking for {} ...", key);

            WebElement row = waitForSocioRow(key);

            log.info("Found socio row: data-nombre='{}' data-licencia='{}' data-idsocio='{}'",
                    row.getAttribute("data-nombre"), row.getAttribute("data-licencia"), row.getAttribute("data-idsocio"));
            WebElement plusIcon;
            try {
                plusIcon = row.findElement(By.xpath(".//i[contains(@class,'fa-plus-circle')] | .//td[contains(@class,'right')]//i"));
            } catch (NoSuchElementException e) {
                throw new NoSuchElementException("Found socio row but could not find plus icon (<i>) for data-licencia=" + key);
            }

            String memberId = row.getAttribute("data-idsocio");
            String memberName = row.getAttribute("data-nombre");
                if (memberName != null && !memberName.isBlank()) {
                    observedSocioNames.put(key, memberName.trim());
                }
            long beforeAddition = selectedSocioEvidence(key, memberId, memberName);
            log.info("Clicking plus icon for socio data-licencia={}", key);
            clickWithScrollAndJsFallback(plusIcon, "Add selected socio");
            // After adding, the popup often closes. Ensure we don't get stuck behind the modal/overlay.
            try {
                new WebDriverWait(driver, Duration.ofSeconds(2))
                        .until(ExpectedConditions.invisibilityOfElementLocated(By.id("popup-amigo")));
            } catch (Exception e) {
                // If it didn't close, click CANCELAR to dismiss it.
                try {
                    WebElement cancelar = driver.findElement(By.xpath(
                            "//div[@id='popup-amigo']//button[.//span[normalize-space()='CANCELAR'] or contains(.,'CANCELAR')]"));
                    clickWithScrollAndJsFallback(cancelar, "Cancelar popup-amigo");
                    new WebDriverWait(driver, Duration.ofSeconds(2))
                            .until(ExpectedConditions.invisibilityOfElementLocated(By.id("popup-amigo")));
                } catch (Exception ignored) {
                    // best effort
                }
            }
            waitForSocioAdded(key, memberId, memberName, beforeAddition);
        }
        log.info("Added all socios to the booking form ... ready to confirm the reservation");

    }

    private WebElement waitForSocioRow(String licence) {
        try {
            return new WebDriverWait(driver, Duration.ofSeconds(5))
                    .pollingEvery(Duration.ofMillis(200))
                    .ignoring(StaleElementReferenceException.class)
                    .until(currentDriver -> {
                        List<WebElement> rows = currentDriver.findElements(By.cssSelector(
                                "#popup-amigo tr[data-licencia][onclick*='addAmigoModal']"));
                        for (WebElement row : rows) {
                            if (!licence.equals(row.getAttribute("data-licencia")) || !row.isDisplayed()) {
                                continue;
                            }
                            WebElement plus = row.findElement(By.xpath(
                                    ".//i[contains(@class,'fa-plus-circle')] | .//td[contains(@class,'right')]//i"));
                            if (plus.isDisplayed() && plus.isEnabled()) {
                                return row;
                            }
                        }
                        return null;
                    });
        } catch (TimeoutException timeout) {
            logSocioDiagnostics("waiting for licence row and plus icon");
            throw new NoSuchElementException(
                    "Timed out after 5 seconds waiting for a visible socio row and clickable plus icon "
                            + "in popup-amigo for data-licencia: " + licence, timeout);
        }
    }

    private long selectedSocioEvidence(String licence, String memberId, String memberName) {
        Object result = ((JavascriptExecutor) driver).executeScript("""
                const licence = arguments[0], memberId = arguments[1];
                if (typeof inscripcionData === 'undefined' || !Array.isArray(inscripcionData.listaSocios)) {
                    return 0;
                }
                const rows = Array.from(document.querySelectorAll('#table-jugadores tr[id]'));
                return inscripcionData.listaSocios.filter(member =>
                    String(member.licencia || '').trim() === licence
                    && (!memberId || String(member.idSocio) === memberId)
                    && rows.some(row => row.id === String(member.idSocio)
                        && row.getClientRects().length > 0)
                ).length;
                """, licence, memberId, memberName);
        if (!(result instanceof Number count)) {
            throw new IllegalStateException("Could not inspect selected-socio form state");
        }
        return count.longValue();
    }

    private void waitForSocioAdded(String licence, String memberId, String memberName, long beforeAddition) {
        try {
            new WebDriverWait(driver, Duration.ofSeconds(5))
                    .pollingEvery(Duration.ofMillis(200))
                    .ignoring(StaleElementReferenceException.class)
                    .until(currentDriver -> selectedSocioEvidence(licence, memberId, memberName) > beforeAddition);
            log.info("Verified selected-socio form update before reservation");
        } catch (TimeoutException timeout) {
            logSocioDiagnostics("verifying selected-socio form update");
            throw new IllegalStateException(
                    "Timed out after 5 seconds verifying socio was added for data-licencia: " + licence
                            + "; reservation will not be submitted", timeout);
        }
    }

    private void logSocioDiagnostics(String stage) {
        try {
            List<WebElement> popups = driver.findElements(By.id("popup-amigo"));
            boolean visible = !popups.isEmpty() && popups.get(0).isDisplayed();
            int rows = driver.findElements(By.cssSelector("#popup-amigo tr[data-licencia]")).size();
            log.warn("Socio timeout while {}: popup visible={}, licence rows={}", stage, visible, rows);
        } catch (WebDriverException diagnosticsError) {
            log.warn("Could not collect socio timeout diagnostics: {}", diagnosticsError.getClass().getSimpleName());
        }
    }

    private void confirmReservation() {
        WebElement reservarBtn;
        try {
            // HTML uses: <button id="EnviarInscripcion"> ... <span>RESERVAR</span>
            reservarBtn = driver.findElement(By.id("EnviarInscripcion"));
        } catch (NoSuchElementException e) {
            // Fallback for nested <span> text: use descendant text (.) rather than text()
            reservarBtn = driver.findElement(By.xpath(
                    "//button[@id='EnviarInscripcion'] | " +
                    "//button[.//span[normalize-space()='RESERVAR']] | " +
                    "//button[contains(.,'RESERVAR')]"
            ));
        }
        reservationSubmitted = true;
        clickWithScrollAndJsFallback(reservarBtn, "RESERVAR");
        log.info("Clicked on reservar button successfully");

        // Wait for server response message and log it (success or error).
        try {
            WebElement alert = new WebDriverWait(driver, Duration.ofSeconds(8)).until(d -> {
                List<WebElement> alerts = d.findElements(By.xpath(
                        "//div[contains(@class,'alert') and " +
                                "(contains(@class,'alert-info') or contains(@class,'alert-success') or " +
                                " contains(@class,'alert-warning') or contains(@class,'alert-danger'))]"
                ));
                return alerts.isEmpty() ? null : alerts.get(0);
            });
            if (alert != null) {
                reservationAlertClass = alert.getAttribute("class");
                reservationAlertText = alert.getText() == null ? null : alert.getText().replaceAll("\\s+", " ").trim();
                log.info("Reservation alert: class='{}' text='{}'", reservationAlertClass, reservationAlertText);
            }
        } catch (Exception e) {
            log.warn("No reservation alert detected within timeout");
        }

        // Store final success boolean for Main.java to use.
        reservationSuccess = computeSuccessFromDom();
        reservationConfirmedAt = Boolean.TRUE.equals(reservationSuccess)
            ? ZonedDateTime.now(ZoneId.of("Europe/Madrid")) : null;
        captureSuccessfulReservationScreenshot();
    }

    private void captureSuccessfulReservationScreenshot() {
        if (!Boolean.TRUE.equals(reservationSuccess)) {
            return;
        }
        try {
            reservationScreenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            if (reservationScreenshot == null || reservationScreenshot.length == 0) {
                reservationScreenshot = null;
                log.warn("Successful reservation screenshot was empty; sending email without attachment");
            } else {
                log.info("Captured successful reservation screenshot ({} bytes)", reservationScreenshot.length);
            }
        } catch (Exception e) {
            reservationScreenshot = null;
            log.warn("Could not capture successful reservation screenshot; booking remains successful", e);
        }
    }

    public byte[] getReservationScreenshot() {
        return reservationScreenshot == null ? null : reservationScreenshot.clone();
    }

    private Map<String, Object> teeTimeOptionDiagnostics(List<WebElement> options) {
        List<Map<String, Object>> labels = new ArrayList<>();
        StringBuilder html = new StringBuilder();
        int blankVisible = 0;
        int domTimes = 0;
        int enabled = 0;
        for (WebElement option : options) {
            String visible = diagnosticTimeLabel(option.getText());
            String dom = diagnosticTimeLabel(option.getAttribute("textContent"));
            if ("blank".equals(dom)) dom = diagnosticTimeLabel(option.getAttribute("innerText"));
            if ("blank".equals(visible)) blankVisible++;
            if (!"blank".equals(dom) && !"non-time".equals(dom)
                    && !"-1".equals(option.getAttribute("value"))) domTimes++;
            if (option.isEnabled()) enabled++;
            if (labels.size() < 100) {
                Map<String, Object> label = new LinkedHashMap<>();
                label.put("visibleText", visible);
                label.put("domText", dom);
                label.put("placeholder", "-1".equals(option.getAttribute("value")));
                label.put("enabled", option.isEnabled());
                labels.add(label);
                html.append("<option");
                if ("-1".equals(option.getAttribute("value"))) html.append(" value=\"-1\"");
                if (!option.isEnabled()) html.append(" disabled");
                if (option.isSelected()) html.append(" selected");
                String text = "blank".equals(dom) ? visible : dom;
                html.append(">")
                    .append("non-time".equals(text) ? "[redacted]" : "blank".equals(text) ? "" : text)
                    .append("</option>");
            }
        }
        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("rawOptionCount", options.size());
        diagnostic.put("enabledOptionCount", enabled);
        diagnostic.put("blankVisibleTextCount", blankVisible);
        diagnostic.put("domTimeCount", domTimes);
        diagnostic.put("options", labels);
        diagnostic.put("optionHtml", html.toString());
        diagnostic.put("htmlTruncated", options.size() > labels.size());
        return diagnostic;
    }

    private String diagnosticTimeLabel(String text) {
        if (text == null || text.isBlank()) return "blank";
        String normalized = text.trim().replaceAll("\\s+", " ");
        return normalized.matches("(?:[01]?\\d|2[0-3]):[0-5]\\d(?: ?\\(\\d{1,3}\\))?")
                ? normalized : "non-time";
    }

    private void captureAvailability(List<WebElement> options, int players) {
        List<Map<String, Object>> offered = new ArrayList<>();
        Pattern labelPattern = Pattern.compile("^(\\d{1,2}:\\d{2})(?:\\s*\\((\\d+)\\))?$");
        for (WebElement option : options) {
            String label = optionVisibleText(option);
            if (label == null || "-1".equals(option.getAttribute("value"))) continue;
            Matcher labelMatch = labelPattern.matcher(label.trim());
            if (!labelMatch.matches()) continue;
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("time", LocalTime.parse(labelMatch.group(1), DateTimeFormatter.ofPattern("H:mm")).toString());
            value.put("places", labelMatch.group(2) == null ? null : Integer.valueOf(labelMatch.group(2)));
            value.put("enabled", option.isEnabled());
            offered.add(value);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("course", selectedRecorrido);
        snapshot.put("capturedAt", ZonedDateTime.now(ZoneId.of("Europe/Madrid")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        snapshot.put("players", players);
        snapshot.put("holes", 18);
        snapshot.put("options", offered);
        availabilitySnapshots.add(snapshot);
        if (offered.isEmpty()) logTeeTimeDiagnostics(players, "initial-empty");
    }

    private void captureAvailabilityBestEffort(List<WebElement> options, int players) {
        try {
            captureAvailability(options, players);
        } catch (RuntimeException exception) {
            log.warn("Availability observation failed; booking selection continues");
        }
    }

    private void captureBookingHolder() {
        try {
            Object observed = ((JavascriptExecutor) driver).executeScript("""
                    if (typeof inscripcionData === 'undefined' || !Array.isArray(inscripcionData.listaSocios)
                        || inscripcionData.listaSocios.length !== 1) return null;
                    const member = inscripcionData.listaSocios[0];
                    const row = Array.from(document.querySelectorAll('#table-jugadores tr[id]'))
                        .find(candidate => candidate.id === String(member.idSocio)
                            && candidate.getClientRects().length > 0);
                    if (!row || !member.licencia) return null;
                    return {name: String(member.nombre || row.getAttribute('data-nombre') || '').trim(),
                        licencia: String(member.licencia).trim()};
                    """);
            if (observed instanceof Map<?, ?> value) {
                Map<String, Object> holder = new LinkedHashMap<>();
                holder.put("name", value.get("name"));
                holder.put("licencia", value.get("licencia"));
                bookingHolder = holder;
            }
        } catch (RuntimeException exception) {
            log.warn("Booking holder identity was not observed");
        }
    }

    public Map<String, Object> getBookingHolder() { return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(bookingHolder)); }

    public List<Map<String, Object>> getAvailabilitySnapshots() {
        return List.copyOf(availabilitySnapshots);
    }

    public Map<String, String> getObservedSocioNames() {
        return Map.copyOf(observedSocioNames);
    }

    public boolean isReservationSubmitted() { return reservationSubmitted; }

    public boolean isBookingUnavailable() { return bookingUnavailable; }

    public ZonedDateTime getBookingFormStartedAt() { return bookingFormStartedAt; }

    public ZonedDateTime getReservationConfirmedAt() {
        return reservationConfirmedAt;
    }

    public ZonedDateTime getReservationBlockedAt() {
        return reservationBlockedAt;
    }

    public String getSelectedHoraDeJuego() {
        return selectedHoraDeJuego;
    }

    public String getSelectedRecorrido() {
        return selectedRecorrido;
    }

    private void clickWithScrollAndJsFallback(WebElement el, String description) {
        try {
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView({block: 'center'});", el);
        } catch (Exception ignored) {
        }
        sleep(Duration.ofMillis(200));
        try {
            el.click();
        } catch (ElementClickInterceptedException e) {
            log.warn("Click intercepted for '{}', using JS click fallback", description);
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", el);
        }
    }

    public boolean isSuccess() {
        if (reservationSuccess != null) {
            return reservationSuccess;
        }
        return computeSuccessFromDom();
    }

    public Boolean getReservationSuccess() {
        return reservationSuccess;
    }

    public String getReservationAlertText() {
        return reservationAlertText;
    }

    public String getReservationAlertClass() {
        return reservationAlertClass;
    }

    /**
     * Visible texts from the "Hora de juego" dropdown (one line per option) once that step has run;
     * {@code null} if booking failed before the dropdown was read.
     */
    public String getHoraDeJuegoDropdownOptions() {
        return horaDeJuegoDropdownOptions;
    }

    private String formatHoraDeJuegoOptionsForEmail(List<WebElement> options) {
        StringBuilder sb = new StringBuilder();
        for (WebElement opt : options) {
            String t = optionVisibleText(opt);
            if (t == null) {
                continue;
            }
            t = t.replaceAll("\\s+", " ").trim();
            if (t.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private boolean computeSuccessFromDom() {
        // Success message appears inside:
        // <div class="alert alert-info"> ... La reserva se ha realizado correctamente </div>
        By successAlert = By.xpath(
                "//div[contains(@class,'alert') and contains(@class,'alert-info') and " +
                        "contains(normalize-space(.),'La reserva se ha realizado correctamente')]");
        try {
            new WebDriverWait(driver, Duration.ofSeconds(3))
                    .until(d -> !d.findElements(successAlert).isEmpty());
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    public String getPageSource() {
        return driver.getPageSource();
    }

    private void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
