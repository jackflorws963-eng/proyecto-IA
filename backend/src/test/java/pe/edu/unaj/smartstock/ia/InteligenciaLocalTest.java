package pe.edu.unaj.smartstock.ia;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Motor propio de la IA: texto, clasificador que aprende en linea y pronostico de demanda. */
class InteligenciaLocalTest {

    @Test
    void normalizaYExtraeRasgos() {
        assertEquals("que errores hay", Texto.normalizar("¿Qué ERRORES hay?"));
        assertTrue(Texto.rasgos("las ventas de hoy").contains("vent"));
        assertTrue(Texto.rasgos("stock bajo").contains("stock_bajo"));
        assertTrue(Texto.parecido("vende dos bolsas de arros", "Arroz 1 kg") >= 0.5, "tolera un error de dictado");
    }

    @Test
    void clasificaFrasesNuevasConLasSemillas() {
        ClasificadorIntenciones c = new ClasificadorIntenciones(mock(JdbcTemplate.class));
        assertEquals("resumen_ventas", c.clasificar("¿cuánto vendimos hoy?").get(0).intencion());
        assertEquals("pronosticar_agotamiento", c.clasificar("¿cuándo se va a acabar el arroz?").get(0).intencion());
        assertEquals("registrar_movimiento", c.clasificar("llegaron 40 bolsas de arroz").get(0).intencion());
        assertEquals("abrir_pantalla", c.clasificar("llévame a inventario").get(0).intencion());
    }

    @Test
    void aprendeEnLineaUnaFraseQueNoEntendia() {
        ClasificadorIntenciones c = new ClasificadorIntenciones(new BaseEnMemoria());
        String frase = "como anda la plata del turno";
        var antes = c.clasificar(frase);
        assertTrue(antes.isEmpty() || !antes.get(0).intencion().equals("resumen_ventas"));
        c.aprender(frase, "resumen_ventas", "USUARIO", "admin");
        assertEquals("resumen_ventas", c.clasificar("como anda la plata del turno de hoy").get(0).intencion());
        assertEquals(1, c.aprendidos());
    }

    /** Sustituto de la base: acepta los INSERT y no tiene ejemplos guardados. */
    static class BaseEnMemoria extends JdbcTemplate {
        @Override
        public int update(String sql, Object... args) { return 1; }

        @Override
        public void query(String sql, org.springframework.jdbc.core.RowCallbackHandler rch) { }
    }

    @Test
    void pronosticaConSuavizadoExponencial() {
        LocalDate hoy = LocalDate.of(2026, 10, 27);
        Map<LocalDate, Long> ventas = new HashMap<>();
        for (int i = 0; i < 21; i++) ventas.put(hoy.minusDays(i), 4L);   // 4 por dia durante 3 semanas
        var p = PronosticoService.pronosticar(1L, "P001", "Pernos", 5, 40, ventas, hoy);
        assertEquals(0, new BigDecimal("4.00").compareTo(p.demandaDiaria()));
        assertEquals(10, p.diasRestantes());
        assertEquals(hoy.plusDays(10), p.fechaAgotamiento());
        assertEquals(4 * 14 + 5 - 40, p.sugeridoReponer());
        assertEquals("ALTA", p.confianza());
    }

    @Test
    void elPronosticoReaccionaAUnCambioDeRitmo() {
        LocalDate hoy = LocalDate.of(2026, 10, 27);
        Map<LocalDate, Long> ventas = new HashMap<>();
        for (int i = 10; i < 30; i++) ventas.put(hoy.minusDays(i), 2L);
        for (int i = 0; i < 10; i++) ventas.put(hoy.minusDays(i), 10L);   // la demanda subio
        var p = PronosticoService.pronosticar(1L, "P001", "Pernos", 0, 100, ventas, hoy);
        assertTrue(p.demandaDiaria().doubleValue() > 9, "da mas peso a lo reciente: " + p.demandaDiaria());
    }

    @Test
    void sinSalidasNoInventaUnPronostico() {
        var p = PronosticoService.pronosticar(1L, "P001", "Pernos", 5, 2, Map.of(), LocalDate.now());
        assertNull(p.diasRestantes());
        assertEquals("SIN_DATOS", p.confianza());
        assertEquals(3, p.sugeridoReponer());
    }

    @Test
    void detectaCuandoElModeloAfirmaUnaAccionQueNoHizo() {
        assertTrue(AsistenteIa.afirmaAccion("Propuse la venta de 3 bolsas de arroz."));
        assertTrue(AsistenteIa.afirmaAccion("Ya registré la entrada."));
        assertTrue(AsistenteIa.afirmaAccion("¿Confirmas la venta?"));
        assertTrue(AsistenteIa.afirmaAccion("Registramos la venta de 3 bolsas."));
        assertTrue(AsistenteIa.afirmaAccion("Se ha emitido el comprobante."));
        assertFalse(AsistenteIa.afirmaAccion("Hoy se vendieron 3 bolsas por S/ 14.40."));
        assertFalse(AsistenteIa.afirmaAccion("¿Me confirmas la cantidad?"), "pedir un dato no es afirmar una accion");
    }

    @Test
    void quitaMuletillasDeConfirmacionEnConsultas() {
        assertEquals("Hoy van 2 ventas por S/ 20.00.",
                AsistenteIa.sinMuletillas("Hoy van 2 ventas por S/ 20.00. Confirma si necesitas más información."));
        assertEquals("El arroz alcanza 6 días.", AsistenteIa.sinMuletillas("El arroz alcanza 6 días. Por favor, confirma para continuar."));
        assertEquals("¿Me confirmas la cantidad?", AsistenteIa.sinMuletillas("¿Me confirmas la cantidad?"));
    }

    @Test
    void soloAceptaUnModeloEnEstaPc() {
        assertTrue(ClienteOllama.esLocal("localhost"));
        assertTrue(ClienteOllama.esLocal("127.0.0.1"));
        assertFalse(ClienteOllama.esLocal("api.ejemplo.com"));
    }
}
