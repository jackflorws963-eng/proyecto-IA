package pe.edu.unaj.smartstock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoLectura;
import pe.edu.unaj.smartstock.entity.Enums.PrioridadAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoSensor;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.DispositivoRepository;
import pe.edu.unaj.smartstock.repository.LecturaRepository;
import pe.edu.unaj.smartstock.repository.SensorRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class IotService {

    private static final Logger log = LoggerFactory.getLogger(IotService.class);

    static final double TEMP_MIN = -40, TEMP_MAX = 80;
    static final double HUM_MIN = 0, HUM_MAX = 100;
    static final double PESO_MIN = -500, PESO_MAX = 100_000;

    static final BigDecimal HISTERESIS = new BigDecimal("0.5");

    private final SensorRepository sensores;
    private final LecturaRepository lecturas;
    private final DispositivoRepository dispositivos;
    private final AlertaService alertas;
    private final EstimacionService estimacion;
    private final VentiladorService ventilador;
    private final TransactionTemplate tx;

    static final String GUARDADA = "GUARDADA";
    static final String DUPLICADA = "DUPLICADA";
    static final String RECHAZADA = "RECHAZADA";
    static final String REINTENTAR = "REINTENTAR";
    static final String SEQ_REUTILIZADA = "SEQ_REUTILIZADA";

    public IotService(SensorRepository sensores, LecturaRepository lecturas, DispositivoRepository dispositivos,
                      AlertaService alertas, EstimacionService estimacion, VentiladorService ventilador,
                     TransactionTemplate tx) {
        this.sensores = sensores;
        this.lecturas = lecturas;
        this.dispositivos = dispositivos;
        this.alertas = alertas;
        this.estimacion = estimacion;
        this.ventilador = ventilador;
        this.tx = tx;
    }

    public LoteLecturasResponse recibir(Long dispositivoId, LoteLecturasRequest lote) {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        String codigo = tx.execute(s -> {
            Dispositivo d = dispositivos.findById(dispositivoId)
                    .orElseThrow(() -> NegocioException.noEncontrado("Dispositivo", dispositivoId));
            d.setUltimoContacto(ahora);
            return d.getCodigo();
        });

        List<ResultadoItem> resultados = new ArrayList<>();
        int guardadas = 0, duplicadas = 0, rechazadas = 0, reenviar = 0;
        for (LecturaItem item : lote.lecturas()) {
            ResultadoItem r = procesarItem(dispositivoId, item, ahora);
            resultados.add(r);
            switch (r.resultado()) {
                case GUARDADA -> guardadas++;
                case DUPLICADA -> duplicadas++;
                case REINTENTAR, SEQ_REUTILIZADA -> reenviar++;
                default -> rechazadas++;
            }
        }
        log.debug("Lote de {}: {} guardadas, {} duplicadas, {} rechazadas, {} a reenviar",
                codigo, guardadas, duplicadas, rechazadas, reenviar);
        return new LoteLecturasResponse(lote.lecturas().size(), guardadas, duplicadas, rechazadas, reenviar,
                seqMaximo(dispositivoId), resultados, ventiladorEncendido(dispositivoId));
    }

    private ResultadoItem procesarItem(Long dispositivoId, LecturaItem item, OffsetDateTime ahora) {
        for (int intento = 1; ; intento++) {
            try {
                return tx.execute(s -> guardarLectura(dispositivoId, item, ahora));
            } catch (DataIntegrityViolationException | ConcurrencyFailureException ex) {
                if (intento >= 2) {
                    log.warn("Conflicto al guardar lectura seq={} tipo={}: {}", item.seq(), item.tipo(), ex.getMessage());
                    return new ResultadoItem(item.seq(), item.tipo(), REINTENTAR, "conflicto temporal");
                }
            } catch (RuntimeException ex) {
                log.error("Error al procesar lectura seq={} tipo={}", item.seq(), item.tipo(), ex);
                return new ResultadoItem(item.seq(), item.tipo(), RECHAZADA, "error al procesar");
            }
        }
    }

    private ResultadoItem guardarLectura(Long dispositivoId, LecturaItem item, OffsetDateTime ahora) {
        Sensor sensor = sensores.findByDispositivoIdAndTipo(dispositivoId, item.tipo()).orElse(null);
        if (sensor == null) return new ResultadoItem(item.seq(), item.tipo(), RECHAZADA, "sensor no registrado");
        if (!sensor.isActivo()) return new ResultadoItem(item.seq(), item.tipo(), RECHAZADA, "sensor desactivado");
        if (item.valor().isNaN() || item.valor().isInfinite()) {
            return new ResultadoItem(item.seq(), item.tipo(), RECHAZADA, "valor no numerico");
        }
        BigDecimal valor = BigDecimal.valueOf(item.valor()).setScale(3, RoundingMode.HALF_UP);

        Optional<Lectura> previa = lecturas.findBySensorIdAndSeq(sensor.getId(), item.seq());
        if (previa.isPresent()) {
            if (previa.get().getValor().compareTo(valor) == 0) {
                return new ResultadoItem(item.seq(), item.tipo(), DUPLICADA, "ya registrada");
            }
            log.warn("Sensor {} envio seq={} ya usado con otro valor: contador del dispositivo reiniciado",
                    sensor.getId(), item.seq());
            return new ResultadoItem(item.seq(), item.tipo(), SEQ_REUTILIZADA, "seq ya usado con otro valor");
        }

        Lectura l = new Lectura();
        l.setSensor(sensor);
        l.setSeq(item.seq());
        l.setValor(valor);
        l.setRecibidoEn(ahora);
        l.setMedidoEn(resolverFecha(item.ts(), ahora));
        String fueraDeRango = validarRangoFisico(item.tipo(), item.valor());
        l.setEstado(fueraDeRango == null ? EstadoLectura.VALIDA : EstadoLectura.FUERA_RANGO);
        l.setObservacion(fueraDeRango);
        l = lecturas.save(l);
        if (l.getEstado() == EstadoLectura.VALIDA) {
            procesar(sensor, l);
        }
        return new ResultadoItem(item.seq(), item.tipo(), GUARDADA, l.getEstado().name());
    }

    private boolean ventiladorEncendido(Long dispositivoId) {
        return dispositivos.findById(dispositivoId).map(Dispositivo::isVentiladorEncendido).orElse(false);
    }

    private Long seqMaximo(Long dispositivoId) {
        long max = 0;
        for (Sensor s : sensores.findByDispositivoIdOrderByTipo(dispositivoId)) {
            Long m = lecturas.maxSeq(s.getId());
            if (m != null && m > max) max = m;
        }
        return max;
    }

    private void procesar(Sensor sensor, Lectura l) {
        Ubicacion u = sensor.getDispositivo().getUbicacion();
        switch (sensor.getTipo()) {
            case TEMPERATURA -> {
                evaluarAmbiente(TipoAlerta.TEMPERATURA, u, l.getValor(), u.getTempMin(), u.getTempMax(), "°C");
                ventilador.evaluarTemperatura(sensor.getDispositivo(), l.getValor());
            }
            case HUMEDAD -> evaluarAmbiente(TipoAlerta.HUMEDAD, u, l.getValor(), u.getHumMin(), u.getHumMax(), "%");
            case PESO -> estimacion.procesarLecturaPeso(sensor, l);
        }
    }

    void evaluarAmbiente(TipoAlerta tipo, Ubicacion u, BigDecimal valor, BigDecimal min, BigDecimal max, String unidad) {
        String clave = AlertaService.clave(tipo, "ubicacion", u.getId());
        if (valor.compareTo(min) < 0 || valor.compareTo(max) > 0) {
            BigDecimal rango = max.subtract(min);
            BigDecimal exceso = valor.compareTo(max) > 0 ? valor.subtract(max) : min.subtract(valor);
            PrioridadAlerta p = exceso.compareTo(rango.multiply(new BigDecimal("0.2"))) > 0
                    ? PrioridadAlerta.ALTA : PrioridadAlerta.MEDIA;
            alertas.abrirOActualizar(tipo, clave, p, tipo.name().charAt(0) + tipo.name().substring(1).toLowerCase()
                    + " en " + u.getNombre() + " = " + valor.stripTrailingZeros().toPlainString() + unidad
                    + " (rango " + min.toPlainString() + "-" + max.toPlainString() + unidad + ")");
        } else if (valor.compareTo(min.add(HISTERESIS)) >= 0 && valor.compareTo(max.subtract(HISTERESIS)) <= 0) {
            alertas.cerrarSiExiste(clave, "valor normalizado " + valor.toPlainString() + unidad);
        }
    }

    static String validarRangoFisico(TipoSensor tipo, double v) {
        return switch (tipo) {
            case TEMPERATURA -> (v < TEMP_MIN || v > TEMP_MAX) ? "temperatura fuera del rango fisico del DHT22" : null;
            case HUMEDAD -> (v < HUM_MIN || v > HUM_MAX) ? "humedad fuera de 0-100%" : null;
            case PESO -> (v < PESO_MIN || v > PESO_MAX) ? "peso fuera del rango de la celda" : null;
        };
    }

    static OffsetDateTime resolverFecha(Long ts, OffsetDateTime ahora) {
        if (ts == null || ts <= 0) return ahora;
        OffsetDateTime t = OffsetDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneOffset.UTC);
        if (t.isAfter(ahora.plus(Duration.ofMinutes(2))) || t.isBefore(ahora.minusDays(7))) return ahora;
        return t;
    }

    @Transactional(readOnly = true)
    public List<UltimaLectura> ultimasLecturas() {
        List<UltimaLectura> r = new ArrayList<>();
        for (Sensor s : sensores.findAllByOrderByIdAsc()) {
            lecturas.findFirstBySensorIdOrderByMedidoEnDesc(s.getId()).ifPresent(l ->
                    r.add(new UltimaLectura(s.getId(), s.getDispositivo().getCodigo(), s.getTipo(),
                            l.getValor(), l.getMedidoEn(), l.getEstado())));
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<PuntoSerie> serie(Long sensorId, OffsetDateTime desde, OffsetDateTime hasta) {
        if (!sensores.existsById(sensorId)) throw NegocioException.noEncontrado("Sensor", sensorId);
        return lecturas.serie(sensorId, desde, hasta).stream()
                .filter(l -> l.getEstado() == EstadoLectura.VALIDA)
                .map(l -> new PuntoSerie(l.getMedidoEn(), l.getValor())).toList();
    }

    @Transactional(readOnly = true)
    public List<LecturaResponse> lecturas(Long sensorId, OffsetDateTime desde, OffsetDateTime hasta) {
        return lecturas.serie(sensorId, desde, hasta).stream().map(LecturaResponse::de).toList();
    }
}
