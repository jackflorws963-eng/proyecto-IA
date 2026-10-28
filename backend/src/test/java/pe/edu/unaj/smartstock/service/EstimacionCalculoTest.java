package pe.edu.unaj.smartstock.service;

import org.junit.jupiter.api.Test;
import pe.edu.unaj.smartstock.exception.NegocioException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class EstimacionCalculoTest {

    @Test
    void calculaCantidadEstimada() {
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
        assertEquals(new BigDecimal("10.00"), EstimacionService.diferenciaPct(new BigDecimal("18"), 20));
        assertEquals(new BigDecimal("300.00"), EstimacionService.diferenciaPct(new BigDecimal("3"), 0));
        assertEquals(new BigDecimal("0.00"), EstimacionService.diferenciaPct(new BigDecimal("20"), 20));
    }
}
