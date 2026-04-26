package com.prat.booking;

import org.openqa.selenium.By;
import org.openqa.selenium.ElementClickInterceptedException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Browser automation for TeeOne golf booking.
 */
public class TeeOneAutomation {

    private static final Logger log = LoggerFactory.getLogger(TeeOneAutomation.class);
    private static final String BASE_URL = "https://members.teeone.golf/prat/reservas/inscripcion/";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PAGE_LOAD_WAIT = Duration.ofSeconds(3);

    private WebDriver driver;
    private WebDriverWait wait;
    private boolean initialized;

    private Boolean reservationSuccess; // null until RESERVAR attempted
    private String reservationAlertClass;
    private String reservationAlertText;
    /** Visible labels from the Hora de juego dropdown after it is read; used in notification emails. */
    private String horaDeJuegoDropdownOptions;

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
        reservationSuccess = null;
        reservationAlertClass = null;
        reservationAlertText = null;
        horaDeJuegoDropdownOptions = null;

        ZoneId madrid = ZoneId.of("Europe/Madrid");
        ZonedDateTime nowMadrid = ZonedDateTime.now(madrid);
        ZonedDateTime loginCompleteBy = nowMadrid.toLocalDate().atTime(LocalTime.of(19, 59, 50)).atZone(madrid);
        ZonedDateTime initDriverAt = loginCompleteBy.minusSeconds(30);
        ZonedDateTime fillAt = nowMadrid.toLocalDate().atTime(LocalTime.of(20, 0, 2)).atZone(madrid);
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

        // Start filling exactly at 20:00:01 Madrid time when possible.
        waitUntilMadrid(fillAt, "fillBookingForm");
        fillBookingForm(data);
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

        selectDropdown("Recorrido", data.getRecorrido());
        sleep(Duration.ofMillis(100));
        selectDropdown("Número de hoyos", "18");
        sleep(Duration.ofMillis(100));
        selectDropdown("Jugadores", String.valueOf(data.getSocios()));
        sleep(Duration.ofMillis(500));
        selectHoraDeJuego(data.getHorario(), data.getSocios());
        sleep(Duration.ofMillis(200));

        log.info("Already selected recorrido: {}, players: {} and horario: {} ... About to click on bloquear button", data.getRecorrido(), data.getSocios(), data.getHorario());

        WebElement bloquearBtn;
        try {
            // Most reliable: the HTML uses id="BtnBloquear"
            bloquearBtn = driver.findElement(By.id("BtnBloquear"));
        } catch (NoSuchElementException e) {
            // Fallback: text is inside a nested <span>, so use descendant text (.) not text()
            bloquearBtn = driver.findElement(By.xpath(
                    "//button[@id='BtnBloquear'] | " +
                    "//button[.//span[normalize-space()='BLOQUEAR']] | " +
                    "//button[contains(.,'BLOQUEAR')]"));
        }

        bloquearBtn.click();
        log.info("Clicked on bloquear button successfully id={} class={}",
                bloquearBtn.getAttribute("id"), bloquearBtn.getAttribute("class"));
        sleep(Duration.ofSeconds(2)); // Page reloads after BLOQUEAR
    }

    private void selectDropdown(String labelOrName, String value) {
        WebElement selectEl = findSelectByLabelOrName(labelOrName);
        if (selectEl == null) {
            try {
                selectEl = driver.findElement(By.xpath(
                        "//label[contains(.,'" + labelOrName + "')]/following::select[1] | " +
                        "//*[contains(text(),'" + labelOrName + "')]/following::select[1]"));
            } catch (NoSuchElementException e) {
                throw new IllegalStateException("Could not find dropdown for: " + labelOrName, e);
            }
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Empty value for dropdown: " + labelOrName);
        }
        String rawWant = stripInvisibleChars(value.trim());
        String wantCanon = canonicalOptionLabel(rawWant);
        String wantCompact = compactOptionKey(rawWant);

        Select select = new Select(selectEl);
        List<WebElement> options = select.getOptions();
        log.info("Dropdown '{}' options (attempting to select '{}', canonical='{}'):", labelOrName, rawWant, wantCanon);
        for (WebElement opt : options) {
            log.info("  - text: '{}' | value: '{}'", optionVisibleText(opt), opt.getAttribute("value"));
        }

        int matchIndex = -1;
        for (int i = 0; i < options.size(); i++) {
            WebElement opt = options.get(i);
            String optRaw = optionVisibleText(opt);
            String textCanon = canonicalOptionLabel(optRaw);
            String textCompact = compactOptionKey(optRaw);
            String val = opt.getAttribute("value");
            boolean byValue = val != null && rawWant.equals(val.trim());
            boolean byCanon = !wantCanon.isEmpty() && wantCanon.equals(textCanon);
            boolean byCompact = wantCompact.length() >= 4 && wantCompact.equals(textCompact);
            if (byValue || byCanon || byCompact) {
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
        WebElement selected = select.getFirstSelectedOption();
        log.info("Dropdown '{}' selected: text='{}' value='{}'", labelOrName, selected.getText(), selected.getAttribute("value"));
    }

    private void selectHoraDeJuego(String sheetHorario, int socios) {
        String raw = sheetHorario == null ? "" : sheetHorario.trim();
        if (raw.isEmpty()) {
            throw new IllegalStateException("Horario from sheet is empty");
        }

        WebElement selectEl = findSelectByLabelOrName("Hora de juego");
        if (selectEl == null) {
            selectEl = driver.findElement(By.xpath(
                    "//label[contains(.,'Hora de juego')]/following::select[1] | " +
                            "//*[contains(text(),'Hora de juego')]/following::select[1]"));
        }

        Select select = new Select(selectEl);
        List<WebElement> options = select.getOptions();
        horaDeJuegoDropdownOptions = formatHoraDeJuegoOptionsForEmail(options);
        log.info("Hora de juego options (sheet='{}', socios={}):", raw, socios);
        for (WebElement opt : options) {
            log.info("  - text: '{}' | value: '{}'", opt.getText(), opt.getAttribute("value"));
        }

        String best = pickBestHoraOptionText(raw, socios, options);
        log.info("Selecting Hora de juego best match: '{}'", best);
        select.selectByVisibleText(best);

        WebElement selected = select.getFirstSelectedOption();
        log.info("Hora de juego selected: text='{}' value='{}'", selected.getText(), selected.getAttribute("value"));
    }

    private String pickBestHoraOptionText(String sheetHorario, int socios, List<WebElement> options) {
        ParsedHorario parsed = ParsedHorario.parse(sheetHorario);
        if (parsed == null) {
            // fallback: exact match by text if we can't parse
            for (WebElement opt : options) {
                if (normalize(opt.getText()).equalsIgnoreCase(normalize(sheetHorario))) {
                    return opt.getText();
                }
            }
            throw new NoSuchElementException("Could not parse Horario and no exact match found: " + sheetHorario);
        }

        List<Integer> preferredNs = allowedSuffixesForSocios(socios);

        // Keep a 1:1 mapping of visible text -> original option text to avoid index mismatches.
        List<String> optionTexts = new ArrayList<>();
        List<String> optionOriginals = new ArrayList<>();
        for (WebElement opt : options) {
            String original = opt.getText();
            String t = normalize(original);
            if (!t.isBlank()) {
                optionTexts.add(t);
                optionOriginals.add(original);
            }
        }

        int baseMinutes = parsed.minutes;
        // Order required by you:
        // exact, then -10, then +10, then -20, then +20 (max ±20)
        int[] orderedMinuteOffsets = new int[]{0, -10, 10, -20, 20};
        for (int offset : orderedMinuteOffsets) {
            int m = baseMinutes + offset;
            if (m < 0 || m >= 24 * 60) continue;
            String hhmm = formatMinutes(m);

            // If the dropdown provides a plain HH:mm option, it is the most exact match.
            String plain = hhmm;
            int idx = indexOfIgnoreCase(optionTexts, plain);
            if (idx >= 0) return optionOriginals.get(idx);

            // Otherwise match HH:mm (N) respecting socios-based preference order.
            for (Integer n : preferredNs) {
                String candidate = hhmm + " (" + n + ")";
                int idx2 = indexOfIgnoreCase(optionTexts, candidate);
                if (idx2 >= 0) return optionOriginals.get(idx2);
            }
        }

        throw new NoSuchElementException("No Hora de juego option found close to '" + sheetHorario + "' within ±20 minutes");
    }

    private int indexOfIgnoreCase(List<String> list, String needle) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).equalsIgnoreCase(needle)) return i;
        }
        return -1;
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

    private String formatMinutes(int minutes) {
        int h = minutes / 60;
        int m = minutes % 60;
        return String.format("%02d:%02d", h, m);
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
            log.info("Adding socio: {} to the booking form", socioName);
            // Text is inside a nested <span>, so avoid contains(text(),...) and prefer the stable container id.
            WebElement addBtn = wait.until(ExpectedConditions.elementToBeClickable(By.xpath(
                    "//div[@id='AmigosSocios']//button | " +
                    "//button[.//span[contains(normalize-space(),'Añadir a otro socio')]] | " +
                    "//button[contains(.,'Añadir a otro socio')]"
            )));
            clickWithScrollAndJsFallback(addBtn, "Añadir a otro socio");
            sleep(Duration.ofSeconds(2));

            // Popup HTML:
            // <div id="popup-amigo"> ... <div id="table-amigo"><table> <tr data-licencia="..." data-nombre="..."> ... </tr>
            // Clicking the <tr> triggers addAmigoModal(this)
            new WebDriverWait(driver, Duration.ofSeconds(3))
                    .until(ExpectedConditions.visibilityOfElementLocated(By.id("popup-amigo")));

            String key = socioName == null ? "" : socioName.trim();
            log.info("Popup-amigo is visible ... about to find the socio row ... looking for {} ...", key);

            if (key.isEmpty()) {
                throw new IllegalStateException("Socio name from sheet is empty");
            }

            WebElement row = null;
            // Only match by data-licencia (license), as provided in the Google Sheet.
            List<By> locators = List.of(
                    By.xpath("//div[@id='popup-amigo']//tr[contains(@onclick,'addAmigoModal') and @data-licencia=\"" + key + "\"]"),
                    By.xpath("//div[@id='popup-amigo']//tr[contains(@onclick,'addAmigoModal') and contains(@data-licencia, \"" + key + "\")]")
            );

            for (By by : locators) {
                List<WebElement> matches = driver.findElements(by);
                if (!matches.isEmpty()) {
                    row = matches.get(0);
                    break;
                }
            }
            if (row == null) {
                throw new NoSuchElementException("Could not find socio by data-licencia in popup-amigo: " + key);
            }

            log.info("Found socio row: data-nombre='{}' data-licencia='{}' data-idsocio='{}'",
                    row.getAttribute("data-nombre"), row.getAttribute("data-licencia"), row.getAttribute("data-idsocio"));
            WebElement plusIcon;
            try {
                plusIcon = row.findElement(By.xpath(".//i[contains(@class,'fa-plus-circle')] | .//td[contains(@class,'right')]//i"));
            } catch (NoSuchElementException e) {
                throw new NoSuchElementException("Found socio row but could not find plus icon (<i>) for data-licencia=" + key);
            }

            log.info("Clicking plus icon for socio data-licencia={}", key);
            try {
                plusIcon.click();
            } catch (Exception clickErr) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", plusIcon);
            }
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
            sleep(Duration.ofMillis(300));
        }
        log.info("Added all socios to the booking form ... ready to confirm the reservation");

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

    private static String formatHoraDeJuegoOptionsForEmail(List<WebElement> options) {
        StringBuilder sb = new StringBuilder();
        for (WebElement opt : options) {
            String t = opt.getText();
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
