package pe.edu.unaj.smartstock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.DispositivoResponse;
import pe.edu.unaj.smartstock.entity.Dispositivo;
import pe.edu.unaj.smartstock.entity.Enums.EstadoLectura;
import pe.edu.unaj.smartstock.entity.Enums.ModoVentilador;
import pe.edu.unaj.smartstock.entity.Enums.TipoSensor;
import pe.edu.unaj.smartstock.entity.Lectura;
import pe.edu.unaj.smartstock.repository.LecturaRepository;
import pe.edu.unaj.smartstock.repository.SensorRepository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;

@Service
public class VentiladorService {

    private static final Logger log = LoggerFactory.getLogger(VentiladorService.class);

    static final BigDecimal HISTERESIS = new BigDecimal("1.0");

    private final DispositivoService dispositivos;
    private final SensorRepository sensores;
    private final LecturaRepository lecturas;
    private final AuditoriaService auditoria;

    public VentiladorService(DispositivoService dispositivos, SensorRepository sensores,
                             LecturaRepository lecturas, AuditoriaService auditoria) {
        this.dispositivos = dispositivos;
        this.sensores = sensores;
        this.lecturas = lecturas;
        this.auditoria = auditoria;
    }

    void evaluarTemperatura(Dispositivo d, BigDecimal temperatura) {
        if (d.getVentiladorModo() != ModoVentilador.AUTO) return;
        BigDecimal max = d.getUbicacion().getTempMax();
        if (temperatura.compareTo(max) > 0) {
            aplicar(d, true, "automatico: " + temperatura.stripTrailingZeros().toPlainString()
                    + " °C supera el maximo de " + max.toPlainString() + " °C");
        } else if (temperatura.compareTo(max.subtract(HISTERESIS)) <= 0) {
            aplicar(d, false, "automatico: temperatura normalizada ("
                    + temperatura.stripTrailingZeros().toPlainString() + " °C)");
        }
    }

    @Transactional
    public DispositivoResponse cambiarModo(Long dispositivoId, ModoVentilador modo) {
        Dispositivo d = dispositivos.buscar(dispositivoId);
        d.setVentiladorModo(modo);
        auditoria.registrar("VENTILADOR_" + modo, "dispositivo", d.getId(), d.getCodigo());
        switch (modo) {
            case ENCENDIDO -> aplicar(d, true, "orden manual");
            case APAGADO -> aplicar(d, false, "orden manual");
            case AUTO -> ultimaLectura(d, TipoSensor.TEMPERATURA).ifPresentOrElse(
                    t -> evaluarTemperatura(d, t),
                    () -> aplicar(d, false, "automatico: sin lecturas de temperatura"));
        }
        return DispositivoResponse.de(d, dispositivos.enLinea(d));
    }

    @Transactional(readOnly = true)
    public Optional<BigDecimal> ultimaLectura(Dispositivo d, TipoSensor tipo) {
        return sensores.findByDispositivoIdAndTipo(d.getId(), tipo)
                .flatMap(s -> lecturas.findFirstBySensorIdOrderByMedidoEnDesc(s.getId()))
                .filter(l -> l.getEstado() == EstadoLectura.VALIDA)
                .map(Lectura::getValor);
    }

    private void aplicar(Dispositivo d, boolean encender, String motivo) {
        if (d.isVentiladorEncendido() == encender) return;
        d.setVentiladorEncendido(encender);
        d.setVentiladorCambio(OffsetDateTime.now());
        auditoria.registrar(encender ? "VENTILADOR_ON" : "VENTILADOR_OFF", "dispositivo", d.getId(),
                d.getCodigo() + " | " + motivo);
        log.info("Ventilador de {} {} ({})", d.getCodigo(), encender ? "encendido" : "apagado", motivo);
    }
}
