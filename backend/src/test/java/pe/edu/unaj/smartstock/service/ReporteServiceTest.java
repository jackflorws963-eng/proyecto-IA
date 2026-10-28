package pe.edu.unaj.smartstock.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReporteServiceTest {

    @Test
    void escapaCsv() {
        assertEquals("", ReporteService.escapar(null));
        assertEquals("texto", ReporteService.escapar("texto"));
        assertEquals("\"a;b\"", ReporteService.escapar("a;b"));
        assertEquals("\"dijo \"\"hola\"\"\"", ReporteService.escapar("dijo \"hola\""));
    }

    @Test
    void evitaInyeccionDeFormulas() {
        assertEquals("'=SUMA(A1)", ReporteService.escapar("=SUMA(A1)"));
        assertEquals("-12.5", ReporteService.escapar("-12.5"));
    }
}
