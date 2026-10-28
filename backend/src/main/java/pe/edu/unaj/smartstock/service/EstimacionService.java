package pe.edu.unaj.smartstock.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoDiscrepancia;
import pe.edu.unaj.smartstock.entity.Enums.PrioridadAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoMovimiento;
import pe.edu.unaj.smartstock.entity.Enums.TipoSensor;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Inventario estimado por peso.
 *   peso_neto = lectura - tara
 *   cantidad_estimada = peso_neto / peso_unitario
 *   diferencia_% = |estimada - registrada| / max(registrada, 1) * 100
 * Si la diferencia supera el umbral se abre UNA discrepancia PENDIENTE por sensor.
 * El stock solo cambia si un ADMIN la aprueba (genera un movimiento de AJUSTE).
 */
@Service
public class EstimacionService {

    private final CalibracionRepository calibraciones;
    private final EstimacionRepository estimaciones;
    private final DiscrepanciaRepository discrepancias;
    private final InventarioRepository inventario;
    private final SensorRepository sensores;
    private final UsuarioRepository usuarios;
    private final AlertaService alertas;
    private final AuditoriaService auditoria;
    private final MovimientoService movimientos;
    private final BigDecimal umbralPct;
    private final BigDecimal umbralUnidades;
    private final int lecturasConfirmacion;

    /** Al aprobar, la ultima estimacion del sensor no puede ser mas antigua que esto. */
    static final Duration ANTIGUEDAD_MAXIMA_ESTIMACION = Duration.ofMinutes(10);

    public EstimacionService(CalibracionRepository calibraciones, EstimacionRepository estimaciones,
                             DiscrepanciaRepository discrepancias, InventarioRepository inventario,
                             SensorRepository sensores, UsuarioRepository usuarios, AlertaService alertas,
                             AuditoriaService auditoria, MovimientoService movimientos,
                             @Value("${smartstock.estimacion.umbral-pct:10}") BigDecimal umbralPct,
                             @Value("${smartstock.estimacion.umbral-unidades:1}") BigDecimal umbralUnidades,
                             @Value("${smartstock.estimacion.lecturas-confirmacion:3}") int lecturasConfirmacion) {
        this.calibraciones = calibraciones;
        this.estimaciones = estimaciones;
        this.discrepancias = discrepancias;
        this.inventario = inventario;
        this.sensores = sensores;
        this.usuarios = usuarios;
        this.alertas = alertas;
        this.auditoria = auditoria;
        this.movimientos = movimientos;
        this.umbralPct = umbralPct;
        this.umbralUnidades = umbralUnidades;
        this.lecturasConfirmacion = Math.max(lecturasConfirmacion, 1);
    }

    // ---------- Calculo (funciones puras, probadas con pruebas unitarias) ----------
    public static BigDecimal cantidadEstimada(BigDecimal pesoNetoG, BigDecimal pesoUnitarioG) {
        if (pesoUnitarioG == null || pesoUnitarioG.signum() <= 0) {
            throw NegocioException.invalido("Peso unitario no configurado");
        }
        BigDecimal neto = pesoNetoG.max(BigDecimal.ZERO);   // ruido negativo = 0 unidades
        return neto.divide(pesoUnitarioG, 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal diferenciaPct(BigDecimal estimada, int registrada) {
        BigDecimal base = BigDecimal.valueOf(Math.max(registrada, 1));
        return estimada.subtract(BigDecimal.valueOf(registrada)).abs()
                .multiply(BigDecimal.valueOf(100)).divide(base, 2, RoundingMode.HALF_UP);
    }

    // ---------- Procesamiento de cada lectura de peso ----------
    @Transactional
    public void procesarLecturaPeso(Sensor sensor, Lectura lectura) {
        Producto p = sensor.getProducto();
        if (p == null || p.getPesoUnitarioG() == null) return;   // sensor sin producto configurado
        Ubicacion u = sensor.getDispositivo().getUbicacion();

        BigDecimal tara = calibraciones.findBySensorIdAndVigenteTrue(sensor.getId())
                .map(Calibracion::getTaraG).orElse(BigDecimal.ZERO);
        BigDecimal neto = lectura.getValor().subtract(tara);
        BigDecimal estimada = cantidadEstimada(neto, p.getPesoUnitarioG());
        int registrada = inventario.findByProductoIdAndUbicacionId(p.getId(), u.getId())
                .map(Inventario::getCantidad).orElse(0);
        BigDecimal difPct = diferenciaPct(estimada, registrada);

        Estimacion e = new Estimacion();
        e.setSensor(sensor);
        e.setProducto(p);
        e.setUbicacion(u);
        e.setLectura(lectura);
        e.setPesoNetoG(neto.setScale(3, RoundingMode.HALF_UP));
        e.setCantidadEstimada(estimada);
        e.setCantidadRegistrada(registrada);
        e.setDiferenciaPct(difPct.min(new BigDecimal("999999.99")));
        e = estimaciones.save(e);

        boolean hayDiscrepancia = superaUmbral(estimada, registrada) && confirmadaPorLecturasSeguidas(sensor.getId());

        if (hayDiscrepancia && !discrepancias.existsBySensorIdAndEstado(sensor.getId(), EstadoDiscrepancia.PENDIENTE)) {
            Discrepancia d = new Discrepancia();
            d.setEstimacion(e);
            d.setSensor(sensor);
            d = discrepancias.save(d);
            auditoria.registrar("DETECTAR", "discrepancia", d.getId(),
                    p.getCodigo() + " estimado=" + estimada + " registrado=" + registrada + " dif=" + difPct + "%");
            alertas.abrirOActualizar(TipoAlerta.DISCREPANCIA,
                    AlertaService.clave(TipoAlerta.DISCREPANCIA, "sensor", sensor.getId()),
                    difPct.compareTo(umbralPct.multiply(BigDecimal.valueOf(3))) > 0 ? PrioridadAlerta.ALTA : PrioridadAlerta.MEDIA,
                    "Discrepancia en " + p.getCodigo() + " (" + u.getNombre() + "): estimado " + estimada
                            + " vs registrado " + registrada + " (" + difPct + "%)");
        }
    }

    private boolean superaUmbral(BigDecimal estimada, int registrada) {
        BigDecimal difUnidades = estimada.subtract(BigDecimal.valueOf(registrada)).abs();
        return diferenciaPct(estimada, registrada).compareTo(umbralPct) > 0 && difUnidades.compareTo(umbralUnidades) >= 0;
    }

    /**
     * Una sola lectura no basta (alguien apoya la mano, se coloca un objeto un instante):
     * las ultimas N estimaciones del sensor deben superar el umbral.
     */
    private boolean confirmadaPorLecturasSeguidas(Long sensorId) {
        List<Estimacion> ultimas = estimaciones.findBySensorIdOrderByIdDesc(sensorId, PageRequest.of(0, lecturasConfirmacion));
        return ultimas.size() >= lecturasConfirmacion
                && ultimas.stream().allMatch(x -> superaUmbral(x.getCantidadEstimada(), x.getCantidadRegistrada()));
    }

    // ---------- Revision humana ----------
    @Transactional(readOnly = true)
    public List<DiscrepanciaResponse> listarDiscrepancias(boolean soloPendientes) {
        List<Discrepancia> lista = soloPendientes
                ? discrepancias.findByEstadoOrderByCreatedAtDesc(EstadoDiscrepancia.PENDIENTE)
                : discrepancias.findTop100ByOrderByCreatedAtDesc();
        return lista.stream().map(DiscrepanciaResponse::de).toList();
    }

    /**
     * Aprueba: ajusta el stock registrado a la cantidad que hay AHORA en la balanza, es decir,
     * a la estimacion MAS RECIENTE del sensor (no a la del momento de la deteccion: si despues
     * hubo ventas o entradas, aquella ya no representa lo que hay en el estante).
     * El ajuste es un movimiento AJUSTE_POSITIVO / AJUSTE_NEGATIVO vinculado a la discrepancia
     * y se calcula contra el stock registrado actual.
     */
    @Transactional
    public DiscrepanciaResponse aprobar(Long id, String username, String comentario) {
        Discrepancia d = pendiente(id);
        Estimacion detectada = d.getEstimacion();
        Estimacion e = estimaciones.findFirstBySensorIdOrderByIdDesc(d.getSensor().getId()).orElse(detectada);
        if (!e.getProducto().getId().equals(detectada.getProducto().getId())
                || !e.getUbicacion().getId().equals(detectada.getUbicacion().getId())) {
            throw NegocioException.conflicto("El sensor ya no pesa el mismo producto/ubicacion: rechace esta discrepancia");
        }
        if (e.getCreatedAt().isBefore(OffsetDateTime.now().minus(ANTIGUEDAD_MAXIMA_ESTIMACION))) {
            throw NegocioException.conflicto("No hay lecturas de peso recientes del sensor (ultima: " + e.getCreatedAt()
                    + "). Verifique que el dispositivo este conectado antes de aprobar");
        }
        int objetivo = e.getCantidadEstimada().setScale(0, RoundingMode.HALF_UP).intValue();
        int actual = movimientos.cantidadRegistrada(e.getProducto().getId(), e.getUbicacion().getId());
        int diferencia = objetivo - actual;
        if (diferencia != 0) {
            MovimientoRequest ajuste = new MovimientoRequest(
                    diferencia > 0 ? TipoMovimiento.AJUSTE_POSITIVO : TipoMovimiento.AJUSTE_NEGATIVO,
                    e.getProducto().getId(),
                    diferencia > 0 ? null : e.getUbicacion().getId(),
                    diferencia > 0 ? e.getUbicacion().getId() : null,
                    Math.abs(diferencia),
                    recortar("Ajuste por discrepancia #" + id + " (estimado actual " + e.getCantidadEstimada() + "): "
                            + comentario, 255));
            movimientos.registrarInterno(ajuste, username, true, d);
        }
        cerrarRevision(d, EstadoDiscrepancia.APROBADA, username, comentario
                + (diferencia == 0 ? " (sin ajuste: el stock ya coincidia)" : " (ajuste " + diferencia + ")"));
        return DiscrepanciaResponse.de(d);
    }

    /** Rechaza: el stock registrado se mantiene (p. ej. habia un objeto extra sobre la balanza). */
    @Transactional
    public DiscrepanciaResponse rechazar(Long id, String username, String comentario) {
        Discrepancia d = pendiente(id);
        cerrarRevision(d, EstadoDiscrepancia.RECHAZADA, username, comentario);
        return DiscrepanciaResponse.de(d);
    }

    private void cerrarRevision(Discrepancia d, EstadoDiscrepancia estado, String username, String comentario) {
        Usuario u = usuarios.findByUsername(username).orElseThrow(() -> NegocioException.noEncontrado("Usuario", username));
        d.setEstado(estado);
        d.setRevisadoPor(u);
        d.setRevisadoEn(OffsetDateTime.now());
        d.setComentario(recortar(comentario, 255));
        auditoria.registrar(estado.name(), "discrepancia", d.getId(), d.getComentario());
        alertas.cerrarSiExiste(AlertaService.clave(TipoAlerta.DISCREPANCIA, "sensor", d.getSensor().getId()),
                "discrepancia " + estado.name().toLowerCase());
    }

    private static String recortar(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private Discrepancia pendiente(Long id) {
        Discrepancia d = discrepancias.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Discrepancia", id));
        if (d.getEstado() != EstadoDiscrepancia.PENDIENTE) throw NegocioException.conflicto("La discrepancia ya fue revisada");
        return d;
    }

    // ---------- Calibracion ----------
    @Transactional
    public CalibracionResponse calibrar(CalibracionRequest r, String username) {
        Sensor s = sensores.findById(r.sensorId()).orElseThrow(() -> NegocioException.noEncontrado("Sensor", r.sensorId()));
        if (s.getTipo() != TipoSensor.PESO) throw NegocioException.invalido("Solo se calibran sensores PESO");
        if (r.factor().signum() == 0) throw NegocioException.invalido("El factor no puede ser 0");
        calibraciones.findBySensorIdAndVigenteTrue(s.getId()).ifPresent(c -> {
            c.setVigente(false);
            calibraciones.saveAndFlush(c);   // libera el indice unico parcial antes de insertar la nueva
        });
        Calibracion c = new Calibracion();
        c.setSensor(s);
        c.setFactor(r.factor());
        c.setTaraG(r.taraG());
        c.setPesoPatronG(r.pesoPatronG());
        c.setUsuario(usuarios.findByUsername(username).orElse(null));
        c = calibraciones.save(c);
        auditoria.registrar("CALIBRAR", "sensor", s.getId(), "factor=" + r.factor() + " tara=" + r.taraG()
                + " patron=" + r.pesoPatronG());
        return CalibracionResponse.de(c);
    }

    @Transactional(readOnly = true)
    public List<CalibracionResponse> historialCalibracion(Long sensorId) {
        return calibraciones.findBySensorIdOrderByCreatedAtDesc(sensorId).stream().map(CalibracionResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public List<EstimacionResponse> ultimasEstimaciones(Long sensorId) {
        return estimaciones.findTop50BySensorIdOrderByCreatedAtDesc(sensorId).stream().map(EstimacionResponse::de).toList();
    }
}
