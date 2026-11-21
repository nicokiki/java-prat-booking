package com.prat.booking;

import org.junit.Before;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.Duration;
import java.time.ZonedDateTime;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class TeeOneAutomationTest {
    private TeeOneAutomation automation;
    private WebDriver driver;

    @Before
    public void setUp() throws Exception {
        automation = new TeeOneAutomation();
        driver = mock(WebDriver.class, withSettings().extraInterfaces(JavascriptExecutor.class, TakesScreenshot.class));
        when(driver.findElement(any(By.class))).thenThrow(new NoSuchElementException("Missing fixture element"));
        when(((JavascriptExecutor) driver).executeScript(anyString())).thenReturn(true);
        when(((JavascriptExecutor) driver).executeScript(availabilityReadScript())).thenReturn(java.util.Map.of());
        Field driverField = TeeOneAutomation.class.getDeclaredField("driver");
        driverField.setAccessible(true);
        driverField.set(automation, driver);
        Field recheckDelay = TeeOneAutomation.class.getDeclaredField("diagnosticRecheckDelay");
        recheckDelay.setAccessible(true);
        recheckDelay.set(automation, Duration.ZERO);
        Field observationTimeout = TeeOneAutomation.class.getDeclaredField("diagnosticObservationTimeout");
        observationTimeout.setAccessible(true);
        observationTimeout.set(automation, Duration.ZERO);
    }

    @Test
    public void recorridoWaitsForRequestedOption() throws Exception {
        assertDelayedChoice("Recorrido", "Recorrido", "TEE 1 AMARILLO", "03");
    }

    @Test
    public void emptyAvailabilityRequiresSuccessfulMatchingOpenSystemResponse() throws Exception {
        doReturn(java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1, "systemOpen", true, "timeCount", 0))
            .when((JavascriptExecutor) driver).executeScript(availabilityReadScript());
        invoke("requireSuccessfulEmptyAvailability", new Class<?>[]{});
        List<java.util.Map<String, Object>> invalid = List.of(
                java.util.Map.of(),
                java.util.Map.of("pending", true),
                java.util.Map.of("pending", false, "httpStatus", 503, "apiCode", 1, "systemOpen", true, "timeCount", 0),
                java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", -1, "systemOpen", true, "timeCount", 0),
                java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1, "systemOpen", false, "timeCount", 0),
                java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1, "systemOpen", true, "timeCount", 5));
        for (var response : invalid) {
            doReturn(response).when((JavascriptExecutor) driver).executeScript(availabilityReadScript());
            assertThrows(IllegalStateException.class, () -> invoke("requireSuccessfulEmptyAvailability", new Class<?>[]{}));
        }
        assertTrue(automation.getAvailabilitySnapshots().isEmpty());
        assertFalse(automation.isBookingUnavailable());
    }

    @Test
    public void apiFailureWithBlankDropdownIsTechnicalAndNotPublishedAsEmptyAvailability() throws Exception {
        doReturn(dropdown(option("", "-1"))).when(driver).findElement(By.id("HoraJuego"));
        doReturn(java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", -1,
                "systemOpen", true, "timeCount", 0)).when((JavascriptExecutor) driver).executeScript(availabilityReadScript());
        assertThrows(IllegalStateException.class, () -> invoke("selectHoraDeJuego",
                new Class<?>[]{String.class, int.class}, "13:00", 2));
        assertTrue(automation.getAvailabilitySnapshots().isEmpty());
        assertFalse(automation.isBookingUnavailable());
        assertNull(automation.getReservationBlockedAt());
    }

    @Test
        public void apiTimesWithUnparseableLabelsAreTechnicalAndDoNotCreateEmptySnapshots() throws Exception {
        doReturn(dropdown(option("unexpected label", "13:00"))).when(driver).findElement(By.id("HoraJuego"));
        doReturn(java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1,
            "systemOpen", true, "timeCount", 1)).when((JavascriptExecutor) driver).executeScript(availabilityReadScript());
        assertThrows(IllegalStateException.class, () -> invoke("selectHoraDeJuego",
            new Class<?>[]{String.class, int.class}, "13:00", 2));
        assertTrue(automation.getAvailabilitySnapshots().isEmpty());
        assertFalse(automation.isBookingUnavailable());
        }

        @Test
    public void observerFixtureContainsOnlyPassiveRequestObservation() throws Exception {
        Field script = TeeOneAutomation.class.getDeclaredField("INSTALL_AVAILABILITY_OBSERVER");
        script.setAccessible(true);
        String source = (String) script.get(null);
        assertFalse(source.contains("$.ajax("));
        assertFalse(source.contains("fetch("));
        assertFalse(source.contains(".click("));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "availability-observer-fixture.js"), source);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "availability-read-fixture.js"), availabilityReadScript());
    }

    private String availabilityReadScript() throws Exception {
        Field script = TeeOneAutomation.class.getDeclaredField("READ_AVAILABILITY_RESPONSE");
        script.setAccessible(true);
        return (String) script.get(null);
    }

    @Test
    public void bookingReadsDomTimeWhenSeleniumLabelIsBlank() throws Exception {
        WebElement time = option("", "08:35");
        when(time.getAttribute("textContent")).thenReturn("08:35 (4)");
        doReturn(dropdown(time)).when(driver).findElement(By.id("HoraJuego"));
        invoke("selectHoraDeJuego", new Class<?>[]{String.class, int.class}, "8:45", 2);
        assertEquals("08:35", automation.getSelectedHoraDeJuego());
        assertEquals("08:35 (4)", pickTime("8:45", 2, List.of(time)));
        var captured = (List<?>) automation.getAvailabilitySnapshots().get(0).get("options");
        assertEquals("08:35", ((java.util.Map<?, ?>) captured.get(0)).get("time"));
        assertTrue(((String) invoke("formatHoraDeJuegoOptionsForEmail", new Class<?>[]{List.class}, List.of(time)))
                .contains("08:35 (4)"));
        assertNull(automation.getReservationBlockedAt());
        assertFalse(automation.isReservationSubmitted());
    }

    @Test
    public void capturesStructuredAvailabilityWithoutChoosingOrBooking() throws Exception {
        invoke("captureAvailability", new Class<?>[]{List.class, int.class},
                List.of(option("Choose", "-1"), option("8:25 (4)", "slot"), option("09:00", "plain")), 2);
        var snapshots = automation.getAvailabilitySnapshots();
        assertEquals(1, snapshots.size());
        assertEquals(2, snapshots.get(0).get("players"));
        var options = (List<?>) snapshots.get(0).get("options");
        assertEquals(2, options.size());
        assertEquals("08:25", ((java.util.Map<?, ?>) options.get(0)).get("time"));
        assertEquals(4, ((java.util.Map<?, ?>) options.get(0)).get("places"));
        assertNull(((java.util.Map<?, ?>) options.get(1)).get("places"));
        assertFalse(automation.isReservationSubmitted());
        verify(driver, never()).get(anyString());
    }

    @Test
    public void emptyReadDiagnosticsDistinguishBlankVisibleTextFromDomTimes() throws Exception {
        WebElement hiddenLabel = option("", "opaque-slot-id");
        when(hiddenLabel.getAttribute("textContent")).thenReturn("08:25 (4)");
        WebElement placeholder = option("Choose", "-1");
        WebElement unexpected = option("Private fixture name", "sensitive-fixture-value");
        when(unexpected.getAttribute("textContent")).thenReturn("Private fixture name");

        var diagnostic = (java.util.Map<?, ?>) invoke("teeTimeOptionDiagnostics", new Class<?>[]{List.class},
                List.of(placeholder, hiddenLabel, unexpected));

        assertEquals(3, diagnostic.get("rawOptionCount"));
        assertEquals(1, diagnostic.get("blankVisibleTextCount"));
        assertEquals(1, diagnostic.get("domTimeCount"));
        assertTrue(diagnostic.toString().contains("08:25 (4)"));
        assertFalse(diagnostic.toString().contains("Private fixture name"));
        assertFalse(diagnostic.toString().contains("opaque-slot-id"));
        assertFalse(diagnostic.toString().contains("sensitive-fixture-value"));
        var bounded = (java.util.Map<?, ?>) invoke("teeTimeOptionDiagnostics", new Class<?>[]{List.class},
            java.util.Collections.nCopies(125, hiddenLabel));
        assertEquals(125, bounded.get("rawOptionCount"));
        assertEquals(125, bounded.get("domTimeCount"));
        assertEquals(100, ((List<?>) bounded.get("options")).size());
        verify(hiddenLabel, never()).click();
        assertTrue(automation.getAvailabilitySnapshots().isEmpty());
    }

        @Test
        public void delayedEmptyRecheckReadsNewOptionsWithoutSelectingOrChangingSnapshots() throws Exception {
            WebElement lateTime = option("", "late-slot");
            when(lateTime.getAttribute("textContent")).thenReturn("08:25 (4)");
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(lateTime),
            "TEE 1 AMARILLO", dropdown(option("08:35 (2)", "other-slot")));
        Field course = TeeOneAutomation.class.getDeclaredField("selectedRecorrido");
        course.setAccessible(true);
        course.set(automation, "TEE 1 ROSA");
        invoke("captureAvailability", new Class<?>[]{List.class, int.class}, List.of(), 2);
        course.set(automation, "TEE 1 AMARILLO");

        invoke("recheckEmptyAvailability", new Class<?>[]{});

        verify(lateTime, never()).click();
        verify(bloquear, never()).click();
        verify(lateTime, atLeastOnce()).getText();
        assertEquals("TEE 1 AMARILLO", automation.getSelectedRecorrido());
        assertEquals(1, automation.getAvailabilitySnapshots().size());
        assertEquals(List.of(), automation.getAvailabilitySnapshots().get(0).get("options"));
        assertFalse(automation.isReservationSubmitted());
        assertNull(automation.getReservationBlockedAt());
        assertNull(automation.getSelectedHoraDeJuego());
        verify(driver, never()).findElement(By.id("EnviarInscripcion"));

        doThrow(new WebDriverException("Fixture diagnostic failure")).when(driver).findElement(By.id("Recorrido"));
        invoke("recheckEmptyAvailability", new Class<?>[]{});
        assertEquals("TEE 1 AMARILLO", automation.getSelectedRecorrido());
        assertEquals(List.of(), automation.getAvailabilitySnapshots().get(0).get("options"));
        verify(bloquear, never()).click();
        }

        @Test
    public void failedBookingRechecksOnlyAfterBothCoursesAndNeverRetriesReservation() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("Choose", "-1")),
                "TEE 1 AMARILLO", dropdown(option("Choose", "-1")));
        WebElement courses = driver.findElement(By.id("Recorrido"));
        List<WebElement> courseOptions = new org.openqa.selenium.support.ui.Select(courses).getOptions();
        Field initialized = TeeOneAutomation.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(automation, true);

        try (var config = mockStatic(Config.class)) {
            config.when(Config::skipMadridTimeWait).thenReturn(true);
            NoSuchElementException failure = assertThrows(NoSuchElementException.class, () -> automation.performBooking(
                    new BookingData("20261122", "TEE 1 ROSA", "13:00", 2, List.of())));
            assertTrue(failure.getMessage().contains("Timed out after 3 seconds"));
            assertEquals(1, failure.getSuppressed().length);
        }

        verify(courseOptions.get(0), times(2)).click();
        verify(courseOptions.get(1), times(2)).click();
        verify(bloquear, never()).click();
        assertEquals(2, automation.getAvailabilitySnapshots().size());
        assertEquals("TEE 1 AMARILLO", automation.getSelectedRecorrido());
        assertNull(automation.getSelectedHoraDeJuego());
        assertFalse(automation.isReservationSubmitted());
        verify(driver, never()).findElement(By.id("EnviarInscripcion"));
    }

    @Test
        public void delayedEmptyRecheckDoesNotRunAfterBlockingOrSubmission() throws Exception {
        Field course = TeeOneAutomation.class.getDeclaredField("selectedRecorrido");
        course.setAccessible(true);
        course.set(automation, "TEE 1 ROSA");
        invoke("captureAvailability", new Class<?>[]{List.class, int.class}, List.of(), 2);
        Field blocked = TeeOneAutomation.class.getDeclaredField("reservationBlockedAt");
        blocked.setAccessible(true);
        blocked.set(automation, ZonedDateTime.now());
        clearInvocations(driver);
        invoke("recheckEmptyAvailability", new Class<?>[]{});
        verifyNoInteractions(driver);
        blocked.set(automation, null);
        Field submitted = TeeOneAutomation.class.getDeclaredField("reservationSubmitted");
        submitted.setAccessible(true);
        submitted.set(automation, true);
        invoke("recheckEmptyAvailability", new Class<?>[]{});
        verifyNoInteractions(driver);
        }

        @Test
        public void hoyosWaitsForRequestedOption() throws Exception {
        assertDelayedChoice("Número de hoyos", "Hoyos", "18", "1");
    }

        @Test
        public void informationalInspectionCapturesAllCoursesWithoutChoosingTimeOrBooking() throws Exception {
        AtomicReference<String> course = new AtomicReference<>("");
        WebElement rosa = courseOption("TEE 1 ROSA", "0", course);
        WebElement amarillo = courseOption("TEE 1 AMARILLO", "1", course);
        WebElement verde = courseOption("TEE 1 VERDE", "2", course);
        doReturn(dropdown(rosa, amarillo, verde)).when(driver).findElement(By.id("Recorrido"));
        doReturn(dropdown(option("18", "1"))).when(driver).findElement(By.id("Hoyos"));
        WebElement twoPlayers = option("2", "2");
        doReturn(dropdown(twoPlayers)).when(driver).findElement(By.id("Plazas"));
        WebElement afternoon = option("17:55 (4)", "17:55");
        doReturn(dropdown(afternoon)).when(driver).findElement(By.id("HoraJuego"));

        invoke("inspectAllCourses", new Class<?>[]{});

        assertEquals(List.of("TEE 1 ROSA", "TEE 1 AMARILLO", "TEE 1 VERDE"),
            automation.getAvailabilitySnapshots().stream().map(snapshot -> snapshot.get("course")).toList());
        for (var snapshot : automation.getAvailabilitySnapshots()) {
            assertEquals(2, snapshot.get("players"));
            assertEquals(18, snapshot.get("holes"));
        }
        verify(afternoon, never()).click();
        assertFalse(automation.isReservationSubmitted());
        assertNull(automation.getReservationBlockedAt());
        assertNull(automation.getSelectedHoraDeJuego());
        assertTrue(mockingDetails(driver).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("findElement"))
            .allMatch(invocation -> List.of(By.id("Recorrido"), By.id("Hoyos"), By.id("Plazas"), By.id("HoraJuego"),
                By.id("Número de hoyos"), By.id("Jugadores"), By.id("Hora de juego"))
                .contains(invocation.getArgument(0))));
        Field initialized = TeeOneAutomation.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(automation, true);
        doThrow(new WebDriverException("Fixture is already logged in")).when(driver).findElement(By.cssSelector(
            "input[type='text'], input[name='username'], input[id*='user'], input[placeholder*='Usuario']"));
        try (var config = mockStatic(Config.class)) {
            config.when(Config::skipMadridTimeWait).thenReturn(true);
            automation.performAvailabilityInspection(java.time.LocalDate.of(2026, 11, 17));
        }
        assertTrue(automation.isAvailabilityOnly());
        assertTrue(automation.isInformationalComplete());
        assertEquals(3, automation.getAvailabilitySnapshots().size());
        assertFalse(automation.isReservationSubmitted());
        assertNull(automation.getReservationBlockedAt());
        verify(driver).get(endsWith("20261117"));
        doAnswer(invocation -> {
            if ("TEE 1 VERDE".equals(course.get())) throw new WebDriverException("Fixture course inspection failed");
            return true;
        }).when((JavascriptExecutor) driver).executeScript("return !window.jQuery || window.jQuery.active === 0;");
        try (var config = mockStatic(Config.class)) {
            config.when(Config::skipMadridTimeWait).thenReturn(true);
            assertThrows(IllegalStateException.class,
                () -> automation.performAvailabilityInspection(java.time.LocalDate.of(2026, 11, 17)));
        }
        assertFalse(automation.isInformationalComplete());
        assertEquals(List.of("TEE 1 ROSA", "TEE 1 AMARILLO"), automation.getAvailabilitySnapshots().stream()
            .map(snapshot -> snapshot.get("course")).toList());
        assertFalse(automation.isReservationSubmitted());
        verify(afternoon, never()).click();
        }

        @Test
        public void informationalInspectionKeepsEmptyCoursesAndContinues() throws Exception {
        AtomicReference<String> course = new AtomicReference<>("");
        doReturn(dropdown(courseOption("TEE 1 ROSA", "0", course),
            courseOption("TEE 1 AMARILLO", "1", course), courseOption("TEE 1 VERDE", "2", course)))
            .when(driver).findElement(By.id("Recorrido"));
        doReturn(dropdown(option("18", "1"))).when(driver).findElement(By.id("Hoyos"));
        doReturn(dropdown(option("2", "2"))).when(driver).findElement(By.id("Plazas"));
        WebElement empty = dropdown(option("Choose", "-1"));
        WebElement offered = dropdown(option("09:00 (2)", "09:00"));
        doAnswer(invocation -> java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1,
            "systemOpen", true, "timeCount", course.get().equals("TEE 1 ROSA") ? 0 : 1))
            .when((JavascriptExecutor) driver).executeScript(availabilityReadScript());
        doAnswer(invocation -> course.get().equals("TEE 1 ROSA") ? empty : offered)
            .when(driver).findElement(By.id("HoraJuego"));
        invoke("inspectAllCourses", new Class<?>[]{});
        assertEquals(3, automation.getAvailabilitySnapshots().size());
        assertEquals(List.of(), automation.getAvailabilitySnapshots().get(0).get("options"));
        }

    @Test
    public void jugadoresWaitsForRequestedOption() throws Exception {
        assertDelayedChoice("Jugadores", "Plazas", "2", "2");
    }

    @Test
    public void informationalInspectionSkipsVerdeWhenNotOffered() throws Exception {
        AtomicReference<String> course = new AtomicReference<>("");
        doReturn(dropdown(courseOption("TEE 1 ROSA", "0", course), courseOption("TEE 1 AMARILLO", "1", course)))
                .when(driver).findElement(By.id("Recorrido"));
        doReturn(dropdown(option("18", "1"))).when(driver).findElement(By.id("Hoyos"));
        doReturn(dropdown(option("2", "2"))).when(driver).findElement(By.id("Plazas"));
        WebElement offered = option("17:55 (4)", "17:55");
        doReturn(dropdown(offered)).when(driver).findElement(By.id("HoraJuego"));

        Field initialized = TeeOneAutomation.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.set(automation, true);
        try (var config = mockStatic(Config.class)) {
            config.when(Config::skipMadridTimeWait).thenReturn(true);
            automation.performAvailabilityInspection(java.time.LocalDate.of(2026, 11, 20));
        }

        assertEquals(List.of("TEE 1 ROSA", "TEE 1 AMARILLO"), automation.getAvailabilitySnapshots().stream()
                .map(snapshot -> snapshot.get("course")).toList());
        assertTrue(automation.isInformationalComplete());
        assertEquals("Informational", BookingReport.outcome(automation));
        verify(offered, never()).click();
        assertFalse(automation.isReservationSubmitted());
        assertNull(automation.getSelectedHoraDeJuego());
    }

    @Test
    public void timeWaitsPastBlankPlaceholder() throws Exception {
        WebElement placeholder = option("", "-1");
        WebElement time = option("08:45 (2)", "slot");
        WebElement dropdown = dropdown(placeholder);
        when(dropdown.findElements(any(By.class))).thenReturn(List.of(placeholder))
            .thenReturn(List.of(placeholder, time));
        doReturn(dropdown).when(driver).findElement(By.id("HoraJuego"));

        assertSame(dropdown, await("Hora de juego", candidate ->
                !candidate.getText().isBlank() && !"-1".equals(candidate.getAttribute("value"))));
        verify(dropdown, times(2)).findElements(any(By.class));
    }

    @Test
    public void waitsForDropdownToBeEnabled() throws Exception {
        WebElement dropdown = dropdown(option("2", "2"));
        when(dropdown.isEnabled()).thenReturn(false, true);
        doReturn(dropdown).when(driver).findElement(By.id("Plazas"));

        assertSame(dropdown, await("Jugadores", candidate -> true));
        verify(dropdown, times(2)).isEnabled();
    }

    @Test(timeout = 6000)
    public void emptyTimeDropdownFailsWithReadinessTimeout() throws Exception {
        WebElement dropdown = dropdown(option("", "-1"));
        doReturn(dropdown).when(driver).findElement(By.id("HoraJuego"));
        doReturn(java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1, "systemOpen", true, "timeCount", 0))
            .when((JavascriptExecutor) driver).executeScript(availabilityReadScript());

        try {
            invoke("selectHoraDeJuego", new Class<?>[]{String.class, int.class}, "8:45", 2);
            fail("Expected timeout for an empty time dropdown");
        } catch (NoSuchElementException expected) {
            assertTrue(expected.getMessage().contains("Timed out after 3 seconds"));
            assertTrue(expected.getMessage().contains("Hora de juego"));
            assertTrue(expected.getMessage().contains("non-placeholder time option"));
        }
    }

    @Test
    public void waitsForRequestedOptionToBeEnabled() throws Exception {
        WebElement choice = option("2", "2");
        when(choice.isEnabled()).thenReturn(false, true);
        WebElement dropdown = dropdown(choice);
        doReturn(dropdown).when(driver).findElement(By.id("Plazas"));

        assertSame(dropdown, await("Jugadores", candidate -> true));
        verify(choice, times(2)).isEnabled();
    }

    @Test
    public void refindsDropdownAfterDomReplacement() throws Exception {
        WebElement stale = dropdown(option("2", "2"));
        when(stale.isDisplayed()).thenThrow(new StaleElementReferenceException("Replaced"));
        WebElement fresh = dropdown(option("2", "2"));
        doReturn(stale, fresh).when(driver).findElement(By.id("Plazas"));

        assertSame(fresh, await("Jugadores", candidate -> true));
        verify(driver, times(2)).findElement(By.id("Plazas"));
    }

    @Test
    public void timePreferencesRemainExactThenEarlierThenLater() throws Exception {
        assertEquals("08:45 (3)", bestTime(List.of(
                option("08:35 (4)", "earlier"), option("08:45 (2)", "exact2"),
                option("08:45 (3)", "exact3"))));
        assertEquals("08:35 (2)", bestTime(List.of(
                option("08:55 (4)", "later"), option("08:35 (2)", "earlier"))));
    }

    @Test
        public void ranksRosaAndAmarilloSchedulesWithinNinetyMinutes() throws Exception {
        assertRankedTimes("8:30", "08:30", "08:20", "08:40", "08:10", "08:50", "08:00",
            "09:00", "09:10", "09:20", "09:30", "09:40", "09:50", "10:00");
        assertRankedTimes("8:30", "08:25", "08:35", "08:15", "08:45", "08:05", "08:55",
            "09:05", "09:15", "09:25", "09:35", "09:45", "09:55");
        assertRankedTimes("8:35", "08:30", "08:40", "08:20", "08:50", "08:10", "09:00",
                "08:00", "09:10", "09:20", "09:30");
        assertRankedTimes("8:35", "08:35", "08:25", "08:45", "08:15", "08:55", "08:05",
                "09:05", "09:15", "09:25", "09:35");
        assertRankedTimes("8:00", "08:00", "08:10", "08:20", "08:30", "08:40", "08:50", "09:00",
            "09:10", "09:20", "09:30");
        assertRankedTimes("8:00", "08:05", "08:15", "08:25", "08:35", "08:45", "08:55",
            "09:05", "09:15", "09:25");
    }

    @Test
    public void windowIsInclusiveAndCapacityAndDisabledOptionsAreRespected() throws Exception {
        WebElement disabled = option("09:00 (4)", "disabled");
        when(disabled.isEnabled()).thenReturn(false);
        List<WebElement> options = List.of(disabled, option("09:00 (1)", "too-small"),
                option("07:29 (4)", "too-early"), option("10:31 (4)", "too-late"),
                option("10:30 (4)", "later-boundary"), option("07:30 (2)", "earlier-boundary"));
            assertEquals("07:30 (2)", pickTime("9:00", 2, options));
            assertEquals("10:30 (4)", pickTime("9:00", 3, options));
            for (String outside : List.of("07:29", "10:31")) {
                try {
                    pickTime("9:00", 2, List.of(option(outside + " (4)", outside)));
                    fail("Expected a slot 91 minutes away to be rejected: " + outside);
                } catch (NoSuchElementException expected) {
                    assertTrue(expected.getMessage().contains("+/-90 minutes"));
                }
            }
        assertEquals("08:25 (4)", pickTime("8:30", 2, List.of(
                option("08:35 (4)", "later"), option("08:25 (2)", "two"),
                option("08:25 (4)", "four"))));
    }

    @Test
    public void ranksOffGridTimesWithoutAssumingVerdeSchedule() throws Exception {
        assertRankedTimes("8:30", "08:27", "08:34", "08:21");
        assertRankedTimes("8:30", "8:25", "08:35");
        assertRankedTimes("0:10", "00:00", "01:10");
        assertRankedTimes("23:00", "23:59");
        assertNull(invoke("alternateRecorrido", new Class<?>[]{String.class}, "TEE 1 VERDE"));
    }

    @Test
    public void dropdownDriverFailureDoesNotTryAnotherCourse() throws Exception {
        WebElement brokenOption = option("08:30 (4)", "08:30");
        when(brokenOption.getText()).thenThrow(new WebDriverException("Time options failed"));
        when(brokenOption.getAttribute("innerText")).thenThrow(new WebDriverException("Time options failed"));
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(brokenOption),
                "TEE 1 AMARILLO", dropdown(option("08:25 (4)", "08:25")));

        try {
            fillForm("TEE 1 ROSA", "8:30");
            fail("Expected driver failure");
        } catch (WebDriverException expected) {
            assertTrue(expected.getMessage().contains("Time options failed"));
        }

        assertEquals("TEE 1 ROSA", automation.getSelectedRecorrido());
        verify(driver, times(1)).findElement(By.id("HoraJuego"));
        verify(bloquear, never()).click();
    }

    @Test
    public void fallsBackFromRosaToAmarilloBeforeBlocking() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("10:05 (4)", "10:05")),
                "TEE 1 AMARILLO", dropdown(option("08:25 (2)", "08:25")));

        fillForm("TEE 1 ROSA", "8:30");

        assertEquals("TEE 1 AMARILLO", automation.getSelectedRecorrido());
        assertEquals("08:25", automation.getSelectedHoraDeJuego());
        verify(bloquear, times(1)).click();
        verify(driver, times(2)).findElement(By.id("Hoyos"));
        verify(driver, times(2)).findElement(By.id("Plazas"));
    }

    @Test
    public void fallsBackFromAmarilloToRosaBeforeBlocking() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 AMARILLO", dropdown(option("10:05 (4)", "10:05")),
                "TEE 1 ROSA", dropdown(option("08:30 (2)", "08:30")));

        fillForm("TEE 1 AMARILLO", "8:30");

        assertEquals("TEE 1 ROSA", automation.getSelectedRecorrido());
        assertEquals("08:30", automation.getSelectedHoraDeJuego());
        verify(bloquear, times(1)).click();
    }

    @Test
    public void requestedCourseTakesPriorityOverCloserAlternateTime() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("10:00 (2)", "10:00")),
                "TEE 1 AMARILLO", dropdown(option("08:25 (4)", "08:25")));

        fillForm("TEE 1 ROSA", "8:30");

        assertEquals("TEE 1 ROSA", automation.getSelectedRecorrido());
        assertEquals("10:00", automation.getSelectedHoraDeJuego());
        verify(driver, times(1)).findElement(By.id("HoraJuego"));
        verify(bloquear, times(1)).click();
    }

    @Test
    public void neitherCourseAvailableDoesNotBlockOrReserve() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("10:05 (4)", "10:05")),
            "TEE 1 AMARILLO", dropdown(option("10:15 (4)", "10:15")));

        try {
            fillForm("TEE 1 ROSA", "8:30");
            fail("Expected no availability on either course");
        } catch (NoSuchElementException expected) {
            assertTrue(expected.getMessage().contains("+/-90 minutes"));
            assertEquals(1, expected.getSuppressed().length);
        }

        verify(bloquear, never()).click();
        verify(driver, never()).findElement(By.id("EnviarInscripcion"));
        assertNull(automation.getSelectedHoraDeJuego());
        assertNull(automation.getReservationBlockedAt());
    }

    @Test(timeout = 12000)
    public void emptyRequestedCourseFallsBackAfterReadinessTimeout() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("", "-1")),
                "TEE 1 AMARILLO", dropdown(option("08:25 (2)", "08:25")));

        fillForm("TEE 1 ROSA", "8:30");

        assertEquals("TEE 1 AMARILLO", automation.getSelectedRecorrido());
        verify(bloquear, times(1)).click();
        assertNotNull(automation.getReservationBlockedAt());
    }

    @Test
    public void blockingFailureDoesNotTryAnotherCourse() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("08:30 (4)", "08:30")),
                "TEE 1 AMARILLO", dropdown(option("08:25 (4)", "08:25")));
        doThrow(new WebDriverException("Blocking failed")).when(bloquear).click();

        try {
            fillForm("TEE 1 ROSA", "8:30");
            fail("Expected blocking failure");
        } catch (WebDriverException expected) {
            assertTrue(expected.getMessage().contains("Blocking failed"));
        }

        assertNull(automation.getReservationBlockedAt());
        assertEquals("TEE 1 ROSA", automation.getSelectedRecorrido());
        verify(driver, times(1)).findElement(By.id("HoraJuego"));
        verify(bloquear, times(1)).click();
    }

    @Test
    public void invalidHorarioDoesNotTryAnotherCourse() throws Exception {
        WebElement bloquear = configureCourses("TEE 1 ROSA", dropdown(option("08:30 (4)", "08:30")),
                "TEE 1 AMARILLO", dropdown(option("08:25 (4)", "08:25")));

        try {
            fillForm("TEE 1 ROSA", "invalid");
            fail("Expected invalid sheet time failure");
        } catch (NoSuchElementException expected) {
            assertTrue(expected.getMessage().contains("Could not parse Horario"));
        }

        assertEquals("TEE 1 ROSA", automation.getSelectedRecorrido());
        verify(driver, times(1)).findElement(By.id("HoraJuego"));
        verify(bloquear, never()).click();
    }

    @Test
    public void populatedTimesWithoutMatchStillFail() throws Exception {
        try {
            bestTime(List.of(option("10:16 (4)", "outside-window")));
            fail("Expected no matching time");
        } catch (NoSuchElementException expected) {
            assertTrue(expected.getMessage().contains("No Hora de juego option"));
        }
    }

    @Test
    public void formWaitsForBloquearAndClicksOnce() throws Exception {
        doReturn(dropdown(option("TEE 1 AMARILLO", "03"))).when(driver).findElement(By.id("Recorrido"));
        doReturn(dropdown(option("18", "1"))).when(driver).findElement(By.id("Hoyos"));
        doReturn(dropdown(option("2", "2"))).when(driver).findElement(By.id("Plazas"));
        doReturn(dropdown(option("08:45 (2)", "slot"))).when(driver).findElement(By.id("HoraJuego"));
        WebElement bloquear = mock(WebElement.class);
        when(bloquear.isDisplayed()).thenReturn(true);
        when(bloquear.isEnabled()).thenReturn(false, true);
        AtomicReference<Instant> clickStarted = new AtomicReference<>();
        doAnswer(invocation -> {
            clickStarted.set(Instant.now());
            assertNull(automation.getReservationBlockedAt());
            return null;
        }).when(bloquear).click();
        doReturn(bloquear).when(driver).findElement(By.xpath(
                "//button[@id='BtnBloquear'] | " +
                "//button[.//span[normalize-space()='BLOQUEAR']] | " +
                "//button[contains(.,'BLOQUEAR')]"));

        invoke("fillBookingForm", new Class<?>[]{BookingData.class},
                new BookingData("20261109", "TEE 1 AMARILLO", "8:45", 2, List.of()));

        verify(bloquear, times(2)).isEnabled();
        verify(bloquear, times(1)).click();
        assertEquals("08:45 (2)", automation.getHoraDeJuegoDropdownOptions());
        assertEquals("08:45", automation.getSelectedHoraDeJuego());
        assertEquals("TEE 1 AMARILLO", automation.getSelectedRecorrido());
        assertNotNull(automation.getReservationBlockedAt());
        assertEquals("Europe/Madrid", automation.getReservationBlockedAt().getZone().getId());
        assertFalse(automation.getReservationBlockedAt().toInstant().isAfter(clickStarted.get()));
        assertNull(automation.getReservationConfirmedAt());
    }

    @Test
    public void newBookingClearsPreviousBlockedTimestampBeforeReadingDate() throws Exception {
        Field blocked = TeeOneAutomation.class.getDeclaredField("reservationBlockedAt");
        blocked.setAccessible(true);
        blocked.set(automation, ZonedDateTime.parse("2026-11-11T19:00:20Z"));
        BookingData data = mock(BookingData.class);
        when(data.getFecha()).thenThrow(new IllegalArgumentException("Invalid booking date"));

        try {
            automation.performBooking(data);
            fail("Expected date failure before browser navigation");
        } catch (IllegalArgumentException expected) {
            assertEquals("Invalid booking date", expected.getMessage());
        }

        assertNull(automation.getReservationBlockedAt());
        verify(driver, never()).get(anyString());
    }

    @Test
    public void storesActualFallbackTimeWithoutPlayerCountSuffix() throws Exception {
        doReturn(dropdown(option("08:35 (2)", "08:35"))).when(driver).findElement(By.id("HoraJuego"));

        invoke("selectHoraDeJuego", new Class<?>[]{String.class, int.class}, "8:45", 2);

        assertEquals("08:35", automation.getSelectedHoraDeJuego());
    }

    private void assertDelayedChoice(String label, String id, String text, String value) throws Exception {
        WebElement other = option("unrelated", "other");
        WebElement choice = option(text, value);
        WebElement dropdown = dropdown(other);
        when(dropdown.findElements(any(By.class))).thenReturn(List.of(other))
            .thenReturn(List.of(choice));
        doReturn(dropdown).when(driver).findElement(By.id(id));

        invoke("selectDropdown", new Class<?>[]{String.class, String.class}, label, text);

        verify(dropdown, atLeast(3)).findElements(any(By.class));
    }

    @Test
    public void capturesScreenshotOnlyAfterReservarAndSuccessDetection() throws Exception {
        WebElement reservar = mock(WebElement.class);
        WebElement alert = mock(WebElement.class);
        when(alert.getText()).thenReturn("La reserva se ha realizado correctamente");
        doReturn(reservar).when(driver).findElement(By.id("EnviarInscripcion"));
        when(driver.findElements(any(By.class))).thenReturn(List.of(alert));
        byte[] png = new byte[]{1, 2, 3};
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)).thenAnswer(invocation -> {
            assertNotNull(automation.getReservationConfirmedAt());
            assertEquals("Europe/Madrid", automation.getReservationConfirmedAt().getZone().getId());
            return png;
        });

        invoke("confirmReservation", new Class<?>[]{});

        org.mockito.InOrder order = inOrder(reservar, driver);
        order.verify(reservar).click();
        order.verify(driver).findElements(By.xpath(
                "//div[contains(@class,'alert') and contains(@class,'alert-info') and " +
                        "contains(normalize-space(.),'La reserva se ha realizado correctamente')]"));
        order.verify((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        assertEquals(Boolean.TRUE, automation.getReservationSuccess());
        assertArrayEquals(png, automation.getReservationScreenshot());
        automation.getReservationScreenshot()[0] = 9;
        assertArrayEquals(png, automation.getReservationScreenshot());
    }

    @Test
    public void screenshotFailureDoesNotChangeBookingSuccess() throws Exception {
        setReservationSuccess(Boolean.TRUE);
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES))
                .thenThrow(new WebDriverException("Screenshot unavailable"));

        invoke("captureSuccessfulReservationScreenshot", new Class<?>[]{});

        assertEquals(Boolean.TRUE, automation.getReservationSuccess());
        assertNull(automation.getReservationScreenshot());
    }

    @Test
    public void unsuccessfulReservationHasNoConfirmationTimestamp() throws Exception {
        WebElement reservar = mock(WebElement.class);
        WebElement alert = mock(WebElement.class);
        doReturn(reservar).when(driver).findElement(By.id("EnviarInscripcion"));
        when(driver.findElements(any(By.class))).thenReturn(List.of(alert));
        doReturn(List.of()).when(driver).findElements(By.xpath(
                "//div[contains(@class,'alert') and contains(@class,'alert-info') and " +
                        "contains(normalize-space(.),'La reserva se ha realizado correctamente')]"));

        invoke("confirmReservation", new Class<?>[]{});

        assertEquals(Boolean.FALSE, automation.getReservationSuccess());
        assertNull(automation.getReservationConfirmedAt());
        verify((TakesScreenshot) driver, never()).getScreenshotAs(OutputType.BYTES);
    }

    @Test
    public void doesNotCaptureScreenshotForFailedOrUnconfirmedReservation() throws Exception {
        invoke("captureSuccessfulReservationScreenshot", new Class<?>[]{});
        setReservationSuccess(Boolean.FALSE);
        invoke("captureSuccessfulReservationScreenshot", new Class<?>[]{});

        verify((TakesScreenshot) driver, never()).getScreenshotAs(OutputType.BYTES);
        assertNull(automation.getReservationScreenshot());
    }

    @Test
    public void emptyScreenshotDoesNotChangeBookingSuccess() throws Exception {
        setReservationSuccess(Boolean.TRUE);
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[0]);

        invoke("captureSuccessfulReservationScreenshot", new Class<?>[]{});

        assertEquals(Boolean.TRUE, automation.getReservationSuccess());
        assertNull(automation.getReservationScreenshot());
    }

    private void setReservationSuccess(Boolean success) throws Exception {
        Field successField = TeeOneAutomation.class.getDeclaredField("reservationSuccess");
        successField.setAccessible(true);
        successField.set(automation, success);
    }

    @Test
    public void socioRowWaitsForDelayedRowsAndEnabledPlusIcon() throws Exception {
        WebElement row = mock(WebElement.class);
        WebElement plus = mock(WebElement.class);
        when(row.getAttribute("data-licencia")).thenReturn("TEST123");
        when(row.isDisplayed()).thenReturn(true);
        when(row.findElement(any(By.class))).thenReturn(plus);
        when(plus.isDisplayed()).thenReturn(true);
        when(plus.isEnabled()).thenReturn(false).thenReturn(true);
        when(driver.findElements(By.cssSelector(
                "#popup-amigo tr[data-licencia][onclick*='addAmigoModal']")))
                .thenReturn(List.of()).thenReturn(List.of(row));

        assertSame(row, invoke("waitForSocioRow", new Class<?>[]{String.class}, "TEST123"));

        verify(plus, times(2)).isEnabled();
        verify(plus, never()).click();
    }

    @Test
    public void socioAdditionWaitsForNewFormEvidence() throws Exception {
        JavascriptExecutor javascript = (JavascriptExecutor) driver;
        when(javascript.executeScript(anyString(), eq("TEST123"), eq("42"), eq("Test Member")))
                .thenReturn(0L).thenReturn(1L);

        invoke("waitForSocioAdded", new Class<?>[]{String.class, String.class, String.class, long.class},
                "TEST123", "42", "Test Member", 0L);

        verify(javascript, times(2)).executeScript(anyString(), eq("TEST123"), eq("42"), eq("Test Member"));
    }

    @Test
    public void socioEvidenceChecksTeeOneMemberModelAndPlayerTable() throws Exception {
        when(((JavascriptExecutor) driver).executeScript(
                anyString(), eq("TEST123"), eq("42"), eq("Test Member")))
                .thenAnswer(invocation -> {
                    String script = invocation.getArgument(0);
                    assertTrue(script.contains("inscripcionData.listaSocios.filter"));
                    assertTrue(script.contains("'#table-jugadores tr[id]'"));
                    assertTrue(script.contains("String(member.licencia || '').trim() === licence"));
                    assertTrue(script.contains("String(member.idSocio) === memberId"));
                    assertTrue(script.contains("row.id === String(member.idSocio)"));
                    return 1L;
                });

        assertEquals(1L, invoke("selectedSocioEvidence",
                new Class<?>[]{String.class, String.class, String.class}, "TEST123", "42", "Test Member"));
    }

    @Test
    public void socioRowWaitsThroughStaleRowsAndMissingIcons() throws Exception {
        WebElement stale = mock(WebElement.class);
        when(stale.getAttribute("data-licencia")).thenThrow(new StaleElementReferenceException("Replaced row"));
        WebElement row = mock(WebElement.class);
        WebElement plus = mock(WebElement.class);
        when(row.getAttribute("data-licencia")).thenReturn("TEST123");
        when(row.isDisplayed()).thenReturn(true);
        when(row.findElement(any(By.class))).thenThrow(new NoSuchElementException("Loading icon"))
                .thenReturn(plus);
        when(plus.isDisplayed()).thenReturn(false).thenReturn(true);
        when(plus.isEnabled()).thenReturn(true);
        when(driver.findElements(By.cssSelector(
                "#popup-amigo tr[data-licencia][onclick*='addAmigoModal']")))
                .thenReturn(List.of(stale)).thenReturn(List.of(row));

        assertSame(row, invoke("waitForSocioRow", new Class<?>[]{String.class}, "TEST123"));

        verify(plus, never()).click();
    }

    @Test(timeout = 8000)
    public void missingLicenceTimesOutWithoutSelectingPartialMatch() throws Exception {
        WebElement wrongRow = mock(WebElement.class);
        when(wrongRow.getAttribute("data-licencia")).thenReturn("TEST1234");
        when(driver.findElements(By.cssSelector(
                "#popup-amigo tr[data-licencia][onclick*='addAmigoModal']")))
                .thenReturn(List.of(wrongRow));

        try {
            invoke("waitForSocioRow", new Class<?>[]{String.class}, "TEST123");
            fail("Expected missing exact licence to time out");
        } catch (NoSuchElementException expected) {
            assertTrue(expected.getMessage().contains("Timed out after 5 seconds"));
            assertTrue(expected.getMessage().contains("TEST123"));
        }

        verify(wrongRow, never()).findElement(any(By.class));
        verify(wrongRow, never()).click();
    }

    @Test
    public void addsSocioOnceAndVerifiesTheFormUpdate() throws Exception {
        WebElement plus = configureSocioPopup(true);

        invoke("addSocios", new Class<?>[]{BookingData.class},
                new BookingData("20261114", "TEE 1 AMARILLO", "11:15", 2, List.of("TEST123")));

        verify(plus, times(1)).click();
        verify((JavascriptExecutor) driver, times(2)).executeScript(
                anyString(), eq("TEST123"), eq("42"), eq("Test Member"));
    }

    @Test(timeout = 10000)
    public void unverifiedSocioAdditionFailsWithoutClickingAgainOrReserving() throws Exception {
        WebElement plus = configureSocioPopup(false);

        try {
            invoke("addSocios", new Class<?>[]{BookingData.class},
                    new BookingData("20261114", "TEE 1 AMARILLO", "11:15", 2, List.of("TEST123")));
            fail("Expected unverified addition to prevent reservation");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Timed out after 5 seconds verifying socio was added"));
            assertTrue(expected.getMessage().contains("reservation will not be submitted"));
        }

        verify(plus, times(1)).click();
        verify(driver, never()).findElement(By.id("EnviarInscripcion"));
    }

    @Test
    public void uncertainPlusClickDoesNotUseJavascriptRetry() throws Exception {
        WebElement plus = configureSocioPopup(true);
        doThrow(new WebDriverException("Unknown click outcome")).when(plus).click();

        try {
            invoke("addSocios", new Class<?>[]{BookingData.class},
                    new BookingData("20261114", "TEE 1 AMARILLO", "11:15", 2, List.of("TEST123")));
            fail("Expected uncertain click to stop the run");
        } catch (WebDriverException expected) {
            assertTrue(expected.getMessage().contains("Unknown click outcome"));
        }

        verify(plus, times(1)).click();
        verify((JavascriptExecutor) driver, never()).executeScript(eq("arguments[0].click();"), eq(plus));
        verify(driver, never()).findElement(By.id("EnviarInscripcion"));
    }

    private WebElement configureSocioPopup(boolean updateForm) throws Exception {
        Field waitField = TeeOneAutomation.class.getDeclaredField("wait");
        waitField.setAccessible(true);
        waitField.set(automation, new WebDriverWait(driver, Duration.ofSeconds(5)));
        AtomicReference<Boolean> visible = new AtomicReference<>(false);
        AtomicReference<Boolean> selected = new AtomicReference<>(false);
        WebElement popup = mock(WebElement.class);
        when(popup.isDisplayed()).thenAnswer(invocation -> visible.get());
        doReturn(popup).when(driver).findElement(By.id("popup-amigo"));
        when(driver.findElements(By.id("popup-amigo"))).thenReturn(List.of(popup));
        WebElement addButton = mock(WebElement.class);
        when(addButton.isDisplayed()).thenReturn(true);
        when(addButton.isEnabled()).thenReturn(true);
        doAnswer(invocation -> {
            visible.set(true);
            return null;
        }).when(addButton).click();
        doReturn(addButton).when(driver).findElement(By.xpath(
                "//div[@id='AmigosSocios']//button | " +
                        "//button[.//span[contains(normalize-space(),'Añadir a otro socio')]] | " +
                        "//button[contains(.,'Añadir a otro socio')]"));
        WebElement row = mock(WebElement.class);
        when(row.getAttribute("data-licencia")).thenReturn("TEST123");
        when(row.getAttribute("data-idsocio")).thenReturn("42");
        when(row.getAttribute("data-nombre")).thenReturn("Test Member");
        when(row.isDisplayed()).thenReturn(true);
        WebElement plus = mock(WebElement.class);
        when(plus.isDisplayed()).thenReturn(true);
        when(plus.isEnabled()).thenReturn(true);
        when(row.findElement(any(By.class))).thenReturn(plus);
        when(driver.findElements(By.cssSelector(
                "#popup-amigo tr[data-licencia][onclick*='addAmigoModal']"))).thenReturn(List.of(row));
        doAnswer(invocation -> {
            visible.set(false);
            selected.set(updateForm);
            return null;
        }).when(plus).click();
        when(((JavascriptExecutor) driver).executeScript(
                anyString(), eq("TEST123"), eq("42"), eq("Test Member")))
                .thenAnswer(invocation -> selected.get() ? 1L : 0L);
        return plus;
    }

    private WebElement await(String label, Predicate<WebElement> predicate) throws Exception {
        return (WebElement) invoke("waitUntilDropdownReady",
                new Class<?>[]{String.class, Predicate.class, String.class}, label, predicate, "test option");
    }

    private String bestTime(List<WebElement> options) throws Exception {
        return pickTime("8:45", 2, options);
    }

    private String pickTime(String requested, int socios, List<WebElement> options) throws Exception {
        return (String) invoke("pickBestHoraOptionText",
                new Class<?>[]{String.class, int.class, List.class}, requested, socios, options);
    }

    private void assertRankedTimes(String requested, String... expected) throws Exception {
        List<WebElement> remaining = new ArrayList<>();
        for (String time : expected) {
            remaining.add(0, option(time, time));
        }
        for (String time : expected) {
            String selected = pickTime(requested, 2, remaining);
            assertEquals(time, selected);
            remaining.removeIf(candidate -> selected.equals(candidate.getText()));
        }
    }

    private void fillForm(String recorrido, String horario) throws Exception {
        invoke("fillBookingForm", new Class<?>[]{BookingData.class},
                new BookingData("20261111", recorrido, horario, 2, List.of()));
    }

    private WebElement configureCourses(String primary, WebElement primaryTimes,
                                         String alternate, WebElement alternateTimes) {
        AtomicReference<String> selectedCourse = new AtomicReference<>("");
        WebElement primaryOption = courseOption(primary, "0", selectedCourse);
        WebElement alternateOption = courseOption(alternate, "1", selectedCourse);
        doReturn(dropdown(primaryOption, alternateOption)).when(driver).findElement(By.id("Recorrido"));
        doReturn(dropdown(option("18", "1"))).when(driver).findElement(By.id("Hoyos"));
        doReturn(dropdown(option("2", "2"))).when(driver).findElement(By.id("Plazas"));
        doAnswer(invocation -> primary.equals(selectedCourse.get()) ? primaryTimes : alternateTimes)
                .when(driver).findElement(By.id("HoraJuego"));
        try {
            doAnswer(invocation -> {
                WebElement times = primary.equals(selectedCourse.get()) ? primaryTimes : alternateTimes;
                long count = times.findElements(By.tagName("option")).stream()
                        .filter(option -> !"-1".equals(option.getAttribute("value"))).count();
                return java.util.Map.of("pending", false, "httpStatus", 200, "apiCode", 1, "systemOpen", true, "timeCount", count);
            }).when((JavascriptExecutor) driver).executeScript(availabilityReadScript());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        WebElement bloquear = mock(WebElement.class);
        when(bloquear.isDisplayed()).thenReturn(true);
        when(bloquear.isEnabled()).thenReturn(true);
        doReturn(bloquear).when(driver).findElement(By.xpath(
                "//button[@id='BtnBloquear'] | " +
                        "//button[.//span[normalize-space()='BLOQUEAR']] | " +
                        "//button[contains(.,'BLOQUEAR')]"));
        return bloquear;
    }

    private WebElement courseOption(String label, String index, AtomicReference<String> selectedCourse) {
        WebElement course = option(label, label);
        when(course.getAttribute("index")).thenReturn(index);
        when(course.isSelected()).thenAnswer(invocation -> label.equals(selectedCourse.get()));
        doAnswer(invocation -> {
            selectedCourse.set(label);
            return null;
        }).when(course).click();
        return course;
    }

    private Object invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = TeeOneAutomation.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(automation, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private WebElement dropdown(WebElement... options) {
        WebElement dropdown = mock(WebElement.class);
        when(dropdown.getTagName()).thenReturn("select");
        when(dropdown.isDisplayed()).thenReturn(true);
        when(dropdown.isEnabled()).thenReturn(true);
        when(dropdown.findElements(any(By.class))).thenReturn(List.of(options));
        return dropdown;
    }

    private WebElement option(String text, String value) {
        WebElement option = mock(WebElement.class);
        when(option.getText()).thenReturn(text);
        when(option.getAttribute("value")).thenReturn(value);
        when(option.getAttribute("index")).thenReturn("0");
        when(option.isEnabled()).thenReturn(true);
        when(option.isSelected()).thenReturn(true);
        return option;
    }
}