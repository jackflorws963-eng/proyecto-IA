package pe.edu.unaj.smartstock.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IntentosFallidosTest {

    @Test
    void bloqueaTrasVariosFallosYSeLiberaConExito() {
        IntentosFallidos intentos = new IntentosFallidos();
        for (int i = 1; i < IntentosFallidos.MAX_INTENTOS; i++) intentos.fallo("login:admin@1.2.3.4");
        assertFalse(intentos.bloqueado("login:admin@1.2.3.4"));
        intentos.fallo("login:admin@1.2.3.4");
        assertTrue(intentos.bloqueado("login:admin@1.2.3.4"));
        assertFalse(intentos.bloqueado("login:admin@5.6.7.8"));
        intentos.exito("login:admin@1.2.3.4");
        assertFalse(intentos.bloqueado("login:admin@1.2.3.4"));
    }

    @Test
    void limpiaCaracteresPeligrososEnElLog() {
        assertEquals("ESP32-01??X", DispositivoAuthFilter.paraLog("ESP32-01\r\nX"));
    }
}
