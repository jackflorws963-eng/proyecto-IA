package pe.edu.unaj.smartstock.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.*;
import pe.edu.unaj.smartstock.entity.Enums.PrioridadAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoSensor;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class DispositivoService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DispositivoRepository dispositivos;
    private final SensorRepository sensores;
    private final UbicacionRepository ubicaciones;
    private final ProductoRepository productos;
    private final PasswordEncoder encoder;
    private final AuditoriaService auditoria;
    private final AlertaService alertas;
    private final long minutosOffline;

    public DispositivoService(DispositivoRepository dispositivos, SensorRepository sensores,
                              UbicacionRepository ubicaciones, ProductoRepository productos,
                              PasswordEncoder encoder, AuditoriaService auditoria, AlertaService alertas,
                              @Value("${smartstock.iot.minutos-offline:2}") long minutosOffline) {
        this.dispositivos = dispositivos;
        this.sensores = sensores;
        this.ubicaciones = ubicaciones;
        this.productos = productos;
        this.encoder = encoder;
        this.auditoria = auditoria;
        this.alertas = alertas;
        this.minutosOffline = minutosOffline;
    }

    public boolean enLinea(Dispositivo d) {
        return d.getUltimoContacto() != null
                && Duration.between(d.getUltimoContacto(), OffsetDateTime.now()).toMinutes() < minutosOffline;
    }

    @Transactional(readOnly = true)
    public List<DispositivoResponse> listar() {
        return dispositivos.findAllByOrderByCodigoAsc().stream().map(d -> DispositivoResponse.de(d, enLinea(d))).toList();
    }

    @Transactional
    public DispositivoCredencialResponse crear(DispositivoRequest r) {
        String codigo = r.codigo().trim().toUpperCase();
        if (dispositivos.existsByCodigoIgnoreCase(codigo)) throw NegocioException.conflicto("Ya existe el dispositivo " + codigo);
        Ubicacion u = ubicaciones.findById(r.ubicacionId()).orElseThrow(() -> NegocioException.noEncontrado("Ubicacion", r.ubicacionId()));
        String clave = generarClave();
        Dispositivo d = new Dispositivo();
        d.setCodigo(codigo);
        d.setNombre(r.nombre().trim());
        d.setUbicacion(u);
        d.setApiKeyHash(encoder.encode(clave));
        d = dispositivos.save(d);
        auditoria.registrar("CREAR", "dispositivo", d.getId(), codigo + " en " + u.getNombre());
        return new DispositivoCredencialResponse(DispositivoResponse.de(d, false), clave,
                "Copie la clave ahora: no se volvera a mostrar. Guardela en secrets.h del firmware.");
    }

    @Transactional
    public DispositivoCredencialResponse regenerarClave(Long id) {
        Dispositivo d = buscar(id);
        String clave = generarClave();
        d.setApiKeyHash(encoder.encode(clave));
        auditoria.registrar("REGENERAR_CLAVE", "dispositivo", id, d.getCodigo());
        return new DispositivoCredencialResponse(DispositivoResponse.de(d, enLinea(d)), clave,
                "La clave anterior dejo de funcionar. Actualice secrets.h y vuelva a cargar el firmware.");
    }

    @Transactional
    public DispositivoResponse cambiarEstado(Long id, boolean activo) {
        Dispositivo d = buscar(id);
        d.setActivo(activo);
        auditoria.registrar(activo ? "ACTIVAR" : "DESACTIVAR", "dispositivo", id, d.getCodigo());
        if (!activo) alertas.cerrarSiExiste(AlertaService.clave(TipoAlerta.DISPOSITIVO_OFFLINE, "dispositivo", id), "dispositivo desactivado");
        return DispositivoResponse.de(d, enLinea(d));
    }

    @Transactional(readOnly = true)
    public List<SensorResponse> listarSensores() {
        return sensores.findAllByOrderByIdAsc().stream().map(SensorResponse::de).toList();
    }

    @Transactional
    public SensorResponse crearSensor(SensorRequest r) {
        Dispositivo d = buscar(r.dispositivoId());
        if (sensores.findByDispositivoIdAndTipo(d.getId(), r.tipo()).isPresent()) {
            throw NegocioException.conflicto("El dispositivo ya tiene un sensor " + r.tipo());
        }
        Sensor s = new Sensor();
        s.setDispositivo(d);
        s.setTipo(r.tipo());
        s.setProducto(productoParaSensor(r.tipo(), r.productoId()));
        s = sensores.save(s);
        auditoria.registrar("CREAR", "sensor", s.getId(), d.getCodigo() + " " + r.tipo());
        return SensorResponse.de(s);
    }

    @Transactional
    public SensorResponse editarSensor(Long id, Long productoId, Boolean activo) {
        Sensor s = sensores.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Sensor", id));
        String antes = "producto=" + (s.getProducto() == null ? null : s.getProducto().getId()) + " activo=" + s.isActivo();
        if (s.getTipo() == TipoSensor.PESO && productoId != null) s.setProducto(productoParaSensor(TipoSensor.PESO, productoId));
        if (activo != null) s.setActivo(activo);
        auditoria.registrar("EDITAR", "sensor", id, antes + " -> producto="
                + (s.getProducto() == null ? null : s.getProducto().getId()) + " activo=" + s.isActivo());
        return SensorResponse.de(s);
    }

    private Producto productoParaSensor(TipoSensor tipo, Long productoId) {
        if (tipo != TipoSensor.PESO) {
            if (productoId != null) throw NegocioException.invalido("Solo los sensores PESO se asocian a un producto");
            return null;
        }
        if (productoId == null) throw NegocioException.invalido("Un sensor PESO requiere productoId");
        Producto p = productos.findById(productoId).orElseThrow(() -> NegocioException.noEncontrado("Producto", productoId));
        if (p.getPesoUnitarioG() == null) {
            throw NegocioException.invalido("El producto " + p.getCodigo() + " no tiene peso unitario configurado");
        }
        return p;
    }

    @Scheduled(fixedDelayString = "${smartstock.iot.monitor-ms:30000}", initialDelay = 20000)
    @Transactional
    public void revisarConexion() {
        for (Dispositivo d : dispositivos.findByActivoTrue()) {
            String clave = AlertaService.clave(TipoAlerta.DISPOSITIVO_OFFLINE, "dispositivo", d.getId());
            if (enLinea(d)) {
                alertas.cerrarSiExiste(clave, "dispositivo reconectado");
            } else {
                String desde = d.getUltimoContacto() == null ? "nunca se ha conectado" : "ultimo contacto " + d.getUltimoContacto();
                alertas.abrirOActualizar(TipoAlerta.DISPOSITIVO_OFFLINE, clave, PrioridadAlerta.ALTA,
                        "Dispositivo " + d.getCodigo() + " sin comunicacion (" + desde + ")");
            }
        }
    }

    public Dispositivo buscar(Long id) {
        return dispositivos.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Dispositivo", id));
    }

    private static String generarClave() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
