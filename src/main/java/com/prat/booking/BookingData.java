package com.prat.booking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Booking data read from Google Sheets for a specific date.
 */
public class BookingData {

    private final String fecha; // yyyyMMdd
    private final String recorrido;
    private final String horario;
    private final int socios;
    private final List<String> sociosToAdd;

    public BookingData(String fecha, String recorrido, String horario, int socios, List<String> sociosToAdd) {
        this.fecha = fecha;
        this.recorrido = recorrido;
        this.horario = horario;
        this.socios = socios;
        this.sociosToAdd = Collections.unmodifiableList(new ArrayList<>(sociosToAdd));
    }

    public String getFecha() {
        return fecha;
    }

    public String getRecorrido() {
        return recorrido;
    }

    public String getHorario() {
        return horario;
    }

    public int getSocios() {
        return socios;
    }

    public List<String> getSociosToAdd() {
        return sociosToAdd;
    }
}
