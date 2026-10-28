package pe.edu.unaj.smartstock.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import pe.edu.unaj.smartstock.entity.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Objetos de transferencia (entrada y salida de la API).
 * Las entidades JPA nunca se exponen directamente: asi no se filtran
 * campos internos (por ejemplo hashes) ni se generan ciclos JSON.
 */
public final class Dtos {
    private Dtos() { }

    // ======================= CATEGORIAS =======================
    public record CategoriaRequest(
            @NotBlank @Size(max = 80) String nombre,
            @Size(max = 255) String descripcion,
            Boolean activo) { }

    public record CategoriaResponse(Long id, String nombre, String descripcion, boolean activo) {
        public static CategoriaResponse de(Categoria c) {
            return new CategoriaResponse(c.getId(), c.getNombre(), c.getDescripcion(), c.isActivo());
        }
    }

    // ======================= UBICACIONES =======================
    public record UbicacionRequest(
            @NotBlank @Size(max = 80) String nombre,
            @Size(max = 255) String descripcion,
            @NotNull @DecimalMin("-40") @DecimalMax("80") BigDecimal tempMin,
            @NotNull @DecimalMin("-40") @DecimalMax("80") BigDecimal tempMax,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal humMin,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal humMax,
            Boolean activo) { }

    public record UbicacionResponse(Long id, String nombre, String descripcion,
                                    BigDecimal tempMin, BigDecimal tempMax,
                                    BigDecimal humMin, BigDecimal humMax, boolean activo) {
        public static UbicacionResponse de(Ubicacion u) {
            return new UbicacionResponse(u.getId(), u.getNombre(), u.getDescripcion(),
                    u.getTempMin(), u.getTempMax(), u.getHumMin(), u.getHumMax(), u.isActivo());
        }
    }

    // ======================= PRODUCTOS =======================
    public record ProductoRequest(
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "solo letras, numeros, - y _") String codigo,
            @NotBlank @Size(max = 120) String nombre,
            @Size(max = 255) String descripcion,
            @NotNull Long categoriaId,
            @NotBlank @Size(max = 20) String unidad,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal precio,
            @DecimalMin(value = "0.001") @Digits(integer = 7, fraction = 3) BigDecimal pesoUnitarioG,
            @NotNull @Min(0) Integer stockMinimo) { }

    public record ProductoResponse(Long id, String codigo, String nombre, String descripcion,
                                   Long categoriaId, String categoria, String unidad, BigDecimal precio,
                                   BigDecimal pesoUnitarioG, Integer stockMinimo, boolean activo,
                                   long stockTotal) {
        public static ProductoResponse de(Producto p, long stockTotal) {
            return new ProductoResponse(p.getId(), p.getCodigo(), p.getNombre(), p.getDescripcion(),
                    p.getCategoria().getId(), p.getCategoria().getNombre(), p.getUnidad(), p.getPrecio(),
                    p.getPesoUnitarioG(), p.getStockMinimo(), p.isActivo(), stockTotal);
        }
    }

    public record PaginaResponse<T>(List<T> contenido, int pagina, int tamano, long totalElementos, int totalPaginas) { }

    // ======================= INVENTARIO Y MOVIMIENTOS =======================
    public record InventarioResponse(Long id, Long productoId, String productoCodigo, String productoNombre,
                                     Long ubicacionId, String ubicacion, Integer cantidad,
                                     Integer stockMinimo, OffsetDateTime actualizado) {
        public static InventarioResponse de(Inventario i) {
            return new InventarioResponse(i.getId(), i.getProducto().getId(), i.getProducto().getCodigo(),
                    i.getProducto().getNombre(), i.getUbicacion().getId(), i.getUbicacion().getNombre(),
                    i.getCantidad(), i.getProducto().getStockMinimo(), i.getUpdatedAt());
        }
    }

    public record MovimientoRequest(
            @NotNull Enums.TipoMovimiento tipo,
            @NotNull Long productoId,
            Long ubicacionOrigenId,
            Long ubicacionDestinoId,
            @NotNull @Positive @Max(1_000_000) Integer cantidad,
            @NotBlank @Size(max = 255) String motivo) { }

    public record MovimientoResponse(Long id, Enums.TipoMovimiento tipo, Long productoId, String producto,
                                     String ubicacionOrigen, String ubicacionDestino, Integer cantidad,
                                     String motivo, String usuario, Long discrepanciaId, OffsetDateTime fecha) {
        public static MovimientoResponse de(Movimiento m) {
            return new MovimientoResponse(m.getId(), m.getTipo(), m.getProducto().getId(),
                    m.getProducto().getCodigo() + " - " + m.getProducto().getNombre(),
                    m.getUbicacionOrigen() == null ? null : m.getUbicacionOrigen().getNombre(),
                    m.getUbicacionDestino() == null ? null : m.getUbicacionDestino().getNombre(),
                    m.getCantidad(), m.getMotivo(), m.getUsuario().getUsername(),
                    m.getDiscrepancia() == null ? null : m.getDiscrepancia().getId(), m.getCreatedAt());
        }
    }

    // ======================= DISPOSITIVOS Y SENSORES =======================
    public record DispositivoRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String codigo,
            @NotBlank @Size(max = 80) String nombre,
            @NotNull Long ubicacionId) { }

    public record DispositivoResponse(Long id, String codigo, String nombre, Long ubicacionId, String ubicacion,
                                      boolean activo, OffsetDateTime ultimoContacto, boolean enLinea,
                                      Enums.ModoVentilador ventiladorModo, boolean ventiladorEncendido) {
        public static DispositivoResponse de(Dispositivo d, boolean enLinea) {
            return new DispositivoResponse(d.getId(), d.getCodigo(), d.getNombre(), d.getUbicacion().getId(),
                    d.getUbicacion().getNombre(), d.isActivo(), d.getUltimoContacto(), enLinea,
                    d.getVentiladorModo(), d.isVentiladorEncendido());
        }
    }

    /** Se devuelve UNA sola vez al crear o regenerar la clave del dispositivo. */
    public record DispositivoCredencialResponse(DispositivoResponse dispositivo, String apiKey, String aviso) { }

    public record SensorRequest(
            @NotNull Long dispositivoId,
            @NotNull Enums.TipoSensor tipo,
            Long productoId) { }

    public record SensorResponse(Long id, Long dispositivoId, String dispositivo, Enums.TipoSensor tipo,
                                 Long productoId, String producto, boolean activo) {
        public static SensorResponse de(Sensor s) {
            return new SensorResponse(s.getId(), s.getDispositivo().getId(), s.getDispositivo().getCodigo(),
                    s.getTipo(), s.getProducto() == null ? null : s.getProducto().getId(),
                    s.getProducto() == null ? null : s.getProducto().getNombre(), s.isActivo());
        }
    }

    // ======================= LECTURAS IoT =======================
    /** Una medicion enviada por el ESP32. */
    public record LecturaItem(
            @NotNull Enums.TipoSensor tipo,
            @NotNull Double valor,
            @NotNull @PositiveOrZero Long seq,
            /** Segundos epoch UTC (si el ESP32 sincronizo NTP). Opcional. */
            Long ts) { }

    /** Lote de mediciones: permite enviar las pendientes acumuladas sin conexion. */
    public record LoteLecturasRequest(
            @NotNull @Size(min = 1, max = 100) List<@Valid LecturaItem> lecturas) { }

    /**
     * resultado: GUARDADA, DUPLICADA (ya estaba: el ESP32 la borra), RECHAZADA (reenviarla no sirve),
     * REINTENTAR (conflicto temporal: reenviar con el mismo seq) o
     * SEQ_REUTILIZADA (el contador del ESP32 se reinicio: reenviar con un seq nuevo).
     */
    public record ResultadoItem(Long seq, Enums.TipoSensor tipo, String resultado, String detalle) { }

    /** seqMaximo: mayor seq guardado para el dispositivo; el ESP32 continua desde ahi si su contador es menor. */
    public record LoteLecturasResponse(int recibidas, int guardadas, int duplicadas, int rechazadas, int reenviar,
                                       Long seqMaximo, List<ResultadoItem> resultados,
                                       /* estado que el ESP32 debe aplicar al rele del ventilador */
                                       boolean ventilador) { }

    public record LecturaResponse(Long id, Long sensorId, Enums.TipoSensor tipo, String dispositivo,
                                  BigDecimal valor, Long seq, OffsetDateTime medidoEn,
                                  OffsetDateTime recibidoEn, Enums.EstadoLectura estado, String observacion) {
        public static LecturaResponse de(Lectura l) {
            return new LecturaResponse(l.getId(), l.getSensor().getId(), l.getSensor().getTipo(),
                    l.getSensor().getDispositivo().getCodigo(), l.getValor(), l.getSeq(), l.getMedidoEn(),
                    l.getRecibidoEn(), l.getEstado(), l.getObservacion());
        }
    }

    public record PuntoSerie(OffsetDateTime t, BigDecimal v) { }

    // ======================= CALIBRACION Y ESTIMACION =======================
    public record CalibracionRequest(
            @NotNull Long sensorId,
            @NotNull @Digits(integer = 8, fraction = 6) BigDecimal factor,
            @NotNull @DecimalMin("0") @Digits(integer = 9, fraction = 3) BigDecimal taraG,
            @DecimalMin("0.001") @Digits(integer = 9, fraction = 3) BigDecimal pesoPatronG) { }

    public record CalibracionResponse(Long id, Long sensorId, BigDecimal factor, BigDecimal taraG,
                                      BigDecimal pesoPatronG, String usuario, boolean vigente, OffsetDateTime fecha) {
        public static CalibracionResponse de(Calibracion c) {
            return new CalibracionResponse(c.getId(), c.getSensor().getId(), c.getFactor(), c.getTaraG(),
                    c.getPesoPatronG(), c.getUsuario() == null ? "sistema" : c.getUsuario().getUsername(),
                    c.isVigente(), c.getCreatedAt());
        }
    }

    public record EstimacionResponse(Long id, Long sensorId, Long productoId, String producto, String ubicacion,
                                     BigDecimal pesoNetoG, BigDecimal cantidadEstimada, Integer cantidadRegistrada,
                                     BigDecimal diferenciaPct, OffsetDateTime fecha) {
        public static EstimacionResponse de(Estimacion e) {
            return new EstimacionResponse(e.getId(), e.getSensor().getId(), e.getProducto().getId(),
                    e.getProducto().getNombre(), e.getUbicacion().getNombre(), e.getPesoNetoG(),
                    e.getCantidadEstimada(), e.getCantidadRegistrada(), e.getDiferenciaPct(), e.getCreatedAt());
        }
    }

    public record DiscrepanciaResponse(Long id, Enums.EstadoDiscrepancia estado, EstimacionResponse estimacion,
                                       String revisadoPor, OffsetDateTime revisadoEn, String comentario,
                                       OffsetDateTime fecha) {
        public static DiscrepanciaResponse de(Discrepancia d) {
            return new DiscrepanciaResponse(d.getId(), d.getEstado(), EstimacionResponse.de(d.getEstimacion()),
                    d.getRevisadoPor() == null ? null : d.getRevisadoPor().getUsername(),
                    d.getRevisadoEn(), d.getComentario(), d.getCreatedAt());
        }
    }

    public record RevisionRequest(@NotBlank @Size(max = 255) String comentario) { }

    // ======================= ALERTAS =======================
    public record AlertaResponse(Long id, Enums.TipoAlerta tipo, Enums.PrioridadAlerta prioridad,
                                 Enums.EstadoAlerta estado, String mensaje, Integer ocurrencias,
                                 OffsetDateTime primeraVez, OffsetDateTime ultimaVez,
                                 String reconocidaPor, OffsetDateTime cerradaEn) {
        public static AlertaResponse de(Alerta a) {
            return new AlertaResponse(a.getId(), a.getTipo(), a.getPrioridad(), a.getEstado(), a.getMensaje(),
                    a.getOcurrencias(), a.getPrimeraVez(), a.getUltimaVez(),
                    a.getReconocidaPor() == null ? null : a.getReconocidaPor().getUsername(), a.getCerradaEn());
        }
    }

    // ======================= DASHBOARD Y AUTH =======================
    public record ResumenDashboard(long productosActivos, long productosBajoMinimo, long alertasActivas,
                                   long discrepanciasPendientes, long dispositivosEnLinea, long dispositivosTotal,
                                   long lecturasUltimaHora) { }

    public record UltimaLectura(Long sensorId, String dispositivo, Enums.TipoSensor tipo, BigDecimal valor,
                                OffsetDateTime medidoEn, Enums.EstadoLectura estado) { }

    public record UsuarioActual(String username, String nombreCompleto, String rol) { }

    // ======================= VENTAS =======================
    public record VentaItemRequest(@NotNull Long productoId, @NotNull @Positive @Max(100_000) Integer cantidad) { }

    /** El precio NO viene del navegador: se toma del producto al momento de la venta. */
    public record VentaRequest(
            @NotNull Enums.TipoComprobante tipoComprobante,
            @NotNull Long ubicacionId,
            @Size(max = 11) String clienteDocumento,
            @Size(max = 120) String clienteNombre,
            @NotNull Enums.MetodoPago metodoPago,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal montoRecibido,
            @NotEmpty @Size(max = 100) List<@Valid @NotNull VentaItemRequest> items) { }

    public record AnulacionRequest(@NotBlank @Size(max = 255) String motivo) { }

    public record VentaDetalleResponse(Long productoId, String codigo, String producto, String unidad,
                                       Integer cantidad, BigDecimal precioUnitario, BigDecimal importe) {
        public static VentaDetalleResponse de(VentaDetalle d) {
            return new VentaDetalleResponse(d.getProducto().getId(), d.getProducto().getCodigo(),
                    d.getProducto().getNombre(), d.getProducto().getUnidad(), d.getCantidad(),
                    d.getPrecioUnitario(), d.getImporte());
        }
    }

    public record VentaResponse(Long id, String numero, Enums.TipoComprobante tipoComprobante, Long ubicacionId,
                                String ubicacion, String clienteDocumento, String clienteNombre,
                                Enums.MetodoPago metodoPago, BigDecimal subtotal, BigDecimal igv, BigDecimal total,
                                BigDecimal montoRecibido, BigDecimal vuelto, Enums.EstadoVenta estado,
                                String vendedor, OffsetDateTime fecha, String anuladaPor, OffsetDateTime anuladaEn,
                                String motivoAnulacion, List<VentaDetalleResponse> detalles) {
        public static VentaResponse de(Venta v) {
            return new VentaResponse(v.getId(), v.getNumero(), v.getTipoComprobante(), v.getUbicacion().getId(),
                    v.getUbicacion().getNombre(), v.getClienteDocumento(), v.getClienteNombre(), v.getMetodoPago(),
                    v.getSubtotal(), v.getIgv(), v.getTotal(), v.getMontoRecibido(), v.getVuelto(), v.getEstado(),
                    v.getUsuario().getNombreCompleto(),
                    v.getCreatedAt(), v.getAnuladaPor() == null ? null : v.getAnuladaPor().getUsername(),
                    v.getAnuladaEn(), v.getMotivoAnulacion(),
                    v.getDetalles().stream().map(VentaDetalleResponse::de).toList());
        }
    }

    public record ProductoVendido(String codigo, String nombre, long unidades, BigDecimal importe) { }

    public record ResumenVentas(long ventasHoy, BigDecimal totalHoy, BigDecimal ticketPromedioHoy,
                                long ventasMes, BigDecimal totalMes, List<ProductoVendido> masVendidosMes) { }

    // ======================= ASISTENTE =======================
    /** Accion que el asistente puede ejecutar. Codigos: RECONOCER:{alertaId}, VENTILADOR:{dispositivoId}:{modo}. */
    public record AccionAsistente(String codigo, String descripcion) { }

    /** id estable (p. ej. "alerta:7") para que el frontend sepa que problemas ya anuncio. */
    public record Problema(String id, String tipo, Enums.PrioridadAlerta gravedad, String titulo,
                           String sugerencia, List<AccionAsistente> acciones) { }

    public record DiagnosticoResponse(String resumen, List<Problema> problemas) { }

    public record MensajeAsistenteRequest(@NotBlank @Size(max = 500) String texto) { }

    /** porConfirmar: acciones propuestas que el usuario debe aceptar antes de ejecutarlas. */
    public record RespuestaAsistente(String texto, List<Problema> problemas, List<AccionAsistente> porConfirmar) { }

    public record EjecutarAccionesRequest(@NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 60) String> acciones) { }

    public record AuditoriaResponse(Long id, String usuario, String accion, String entidad, Long entidadId,
                                    String detalle, OffsetDateTime fecha) {
        public static AuditoriaResponse de(Auditoria a) {
            return new AuditoriaResponse(a.getId(), a.getUsername(), a.getAccion(), a.getEntidad(),
                    a.getEntidadId(), a.getDetalle(), a.getCreatedAt());
        }
    }
}
