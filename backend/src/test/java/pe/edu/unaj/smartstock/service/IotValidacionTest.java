package pe.edu.unaj.smartstock.service;

import org.junit.jupiter.api.Test;
import pe.edu.unaj.smartstock.entity.Enums.TipoSensor;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class IotValidacionTest {

    @Test
    void rangosFisicos() {
        assertNull(IotService.validarRangoFisico(TipoSensor.TEMPERATURA, 22.5));
        assertNotNull(IotService.validarRangoFisico(TipoSensor.TEMPERATURA, 150));
        assertNull(IotService.validarRangoFisico(TipoSensor.HUMEDAD, 55));
        assertNotNull(IotService.validarRangoFisico(TipoSensor.HUMEDAD, 101));
        assertNotNull(IotService.validarRangoFisico(TipoSensor.HUMEDAD, -1));
        assertNull(IotService.validarRangoFisico(TipoSensor.PESO, 1000));
        assertNotNull(IotService.validarRangoFisico(TipoSensor.PESO, 200_000));
    }

    @Test
    void usaHoraDelServidorSiNoHayNtp() {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        assertEquals(ahora, IotService.resolverFecha(null, ahora));
        assertEquals(ahora, IotService.resolverFecha(0L, ahora));
    }

    @Test
    void rechazaFechasFuturasOMuyAntiguas() {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        long futuro = ahora.plusHours(1).toEpochSecond();
        long antiguo = ahora.minusDays(30).toEpochSecond();
        assertEquals(ahora, IotService.resolverFecha(futuro, ahora));
        assertEquals(ahora, IotService.resolverFecha(antiguo, ahora));
    }

    @Test
    void aceptaFechaValidaDeLecturaPendiente() {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        long haceDiezMin = ahora.minusMinutes(10).toEpochSecond();
        assertEquals(ahora.minusMinutes(10), IotService.resolverFecha(haceDiezMin, ahora));
    }
}
