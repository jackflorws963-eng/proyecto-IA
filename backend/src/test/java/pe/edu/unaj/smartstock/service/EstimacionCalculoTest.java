package pe.edu.unaj.smartstock.service;

import org.junit.jupiter.api.Test;
import pe.edu.unaj.smartstock.exception.NegocioException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas unitarias del calculo de inventario estimado por peso (sin base de datos). */
class EstimacionCalculoTest {

    @Test
    void calculaCantidadEstimada() {
        // 1000 g netos / 50 g por unidad = 20 unidades
        assertEquals(new BigDecimal("20.00"),
                EstimacionService.cantidadEstimada(new BigDecimal("1000"), new BigDecimal("50")));
    }

    @Test
    void pesoNegativoPorRuidoEsCero() {
        assertEquals(new BigDecimal("0.00"),
                EstimacionService.cantidadEstimada(new BigDecimal("-3.2"), new BigDecimal("50")));
    }

    @Test
    void redondeaADosDecimales() {
        // 1012 / 50 = 20.24
        assertEquals(new BigDecimal("20.24"),
                EstimacionService.cantidadEstimada(new BigDecimal("1012"), new BigDecimal("50")));
    }

    @Test
    void rechazaPesoUnitarioInvalido() {
        assertThrows(NegocioException.class,
                () -> EstimacionService.cantidadEstimada(new BigDecimal("100"), BigDecimal.ZERO));
        assertThrows(NegocioException.class,
                () -> EstimacionService.cantidadEstimada(new BigDecimal("100"), null));
    }

    @Test
    void diferenciaPorcentual() {
        // estimado 18, registrado 20 -> 10 %
        assertEquals(new BigDecimal("10.00"), EstimacionService.diferenciaPct(new BigDecimal("18"), 20));
        // registrado 0: se divide entre 1 para no dividir entre cero
        assertEquals(new BigDecimal("300.00"), EstimacionService.diferenciaPct(new BigDecimal("3"), 0));
        assertEquals(new BigDecimal("0.00"), EstimacionService.diferenciaPct(new BigDecimal("20"), 20));
    }
}
