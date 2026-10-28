package pe.edu.unaj.smartstock.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsistenteTextoTest {

    @Test
    void normalizaTildesSignosYMayusculas() {
        assertEquals("que errores hay", AsistenteService.normalizar("¿Qué ERRORES hay?"));
        assertEquals("enciende el ventilador del esp32 01", AsistenteService.normalizar("Enciende el ventilador del ESP32-01."));
        assertEquals("como esta la temperatura", AsistenteService.normalizar("  ¿Cómo   está la temperatura?  "));
    }

    @Test
    void textoVacioONulo() {
        assertEquals("", AsistenteService.normalizar(null));
        assertEquals("", AsistenteService.normalizar(" ¡! "));
    }
}
