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
import java.util.List;
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
        Field driverField = TeeOneAutomation.class.getDeclaredField("driver");
        driverField.setAccessible(true);
        driverField.set(automation, driver);
    }

    @Test
    public void recorridoWaitsForRequestedOption() throws Exception {
        assertDelayedChoice("Recorrido", "Recorrido", "TEE 1 AMARILLO", "03");
    }

    @Test
    public void hoyosWaitsForRequestedOption() throws Exception {
        assertDelayedChoice("Número de hoyos", "Hoyos", "18", "1");
    }

    @Test
    public void jugadoresWaitsForRequestedOption() throws Exception {
        assertDelayedChoice("Jugadores", "Plazas", "2", "2");
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

    @Test(timeout = 30000)
    public void emptyTimeDropdownFailsWithReadinessTimeout() throws Exception {
        WebElement dropdown = dropdown(option("", "-1"));
        doReturn(dropdown).when(driver).findElement(By.id("HoraJuego"));

        try {
            invoke("selectHoraDeJuego", new Class<?>[]{String.class, int.class}, "8:45", 2);
            fail("Expected timeout for an empty time dropdown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Timed out after 15 seconds"));
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
    public void populatedTimesWithoutMatchStillFail() throws Exception {
        try {
            bestTime(List.of(option("10:00 (4)", "outside-window")));
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
        doReturn(bloquear).when(driver).findElement(By.xpath(
                "//button[@id='BtnBloquear'] | " +
                "//button[.//span[normalize-space()='BLOQUEAR']] | " +
                "//button[contains(.,'BLOQUEAR')]"));

        invoke("fillBookingForm", new Class<?>[]{BookingData.class},
                new BookingData("20261109", "TEE 1 AMARILLO", "8:45", 2, List.of()));

        verify(bloquear, times(2)).isEnabled();
        verify(bloquear, times(1)).click();
        assertEquals("08:45 (2)", automation.getHoraDeJuegoDropdownOptions());
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

    private WebElement await(String label, Predicate<WebElement> predicate) throws Exception {
        return (WebElement) invoke("waitUntilDropdownReady",
                new Class<?>[]{String.class, Predicate.class, String.class}, label, predicate, "test option");
    }

    private String bestTime(List<WebElement> options) throws Exception {
        return (String) invoke("pickBestHoraOptionText",
                new Class<?>[]{String.class, int.class, List.class}, "8:45", 2, options);
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