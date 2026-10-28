package pe.edu.unaj.smartstock.ia;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.EstadoVenta;
import pe.edu.unaj.smartstock.entity.Enums.MetodoPago;
import pe.edu.unaj.smartstock.entity.Enums.ModoVentilador;
import pe.edu.unaj.smartstock.entity.Enums.TipoComprobante;
import pe.edu.unaj.smartstock.entity.Enums.TipoMovimiento;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.service.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * Todo lo que el asistente de IA puede consultar y hacer en SmartStock. Cada herramienta llama a los mismos
 * servicios que usa la interfaz, con el usuario que conversa: se aplican las mismas reglas de negocio,
 * validaciones, auditoria y permisos por rol. Las que modifican datos se proponen y solo se ejecutan
 * cuando la persona confirma (ver AsistenteIa).
 */
@Component
public class Herramientas {

    static final ZoneId ZONA = ZoneId.of("America/Lima");

    /** Pantallas del programa que el asistente puede abrir. */
    static final Map<String, String> PANTALLAS = new LinkedHashMap<>();

    static {
        PANTALLAS.put("dashboard", "/index.html");
        PANTALLAS.put("productos", "/productos.html");
        PANTALLAS.put("inventario", "/inventario.html");
        PANTALLAS.put("ventas", "/ventas.html");
        PANTALLAS.put("dispositivos", "/iot.html");
        PANTALLAS.put("alertas", "/alertas.html");
        PANTALLAS.put("asistente", "/asistente.html");
    }

    /** Usuario que conversa y efectos sobre la interfaz que la herramienta pide al navegador. */
    public static final class Contexto {
        final Authentication auth;
        String navegar;
        String descargar;
        boolean imprimir;

        Contexto(Authentication auth) {
            this.auth = auth;
        }

        String usuario() { return auth.getName(); }

        boolean esAdmin() {
            return auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
        }
    }

    /** Resultado: texto para la persona (y la voz) y datos estructurados para el modelo de lenguaje. */
    public record Resultado(String texto, Object datos) {
        static Resultado de(String texto) { return new Resultado(texto, null); }
    }

    public record Herramienta(String nombre, String descripcion, Map<String, Object> parametros,
                              boolean escritura, boolean soloAdmin,
                              BiFunction<Args, Contexto, String> describir,
                              BiFunction<Args, Contexto, Resultado> ejecutar) { }

    private final Map<String, Herramienta> todas = new LinkedHashMap<>();

    private final ProductoService productos;
    private final CatalogoService catalogo;
    private final MovimientoService movimientos;
    private final VentaService ventas;
    private final AlertaService alertas;
    private final EstimacionService estimacion;
    private final DashboardService dashboard;
    private final DispositivoService dispositivos;
    private final VentiladorService ventilador;
    private final IotService iot;
    private final AuditoriaService auditoria;
    private final AsistenteService diagnostico;
    private final PronosticoService pronostico;
    private final MemoriaIa memoria;
    private final Validator validador;

    public Herramientas(ProductoService productos, CatalogoService catalogo, MovimientoService movimientos,
                        VentaService ventas, AlertaService alertas, EstimacionService estimacion,
                        DashboardService dashboard, DispositivoService dispositivos, VentiladorService ventilador,
                        IotService iot, AuditoriaService auditoria, AsistenteService diagnostico,
                        PronosticoService pronostico, MemoriaIa memoria, Validator validador) {
        this.productos = productos;
        this.catalogo = catalogo;
        this.movimientos = movimientos;
        this.ventas = ventas;
        this.alertas = alertas;
        this.estimacion = estimacion;
        this.dashboard = dashboard;
        this.dispositivos = dispositivos;
        this.ventilador = ventilador;
        this.iot = iot;
        this.auditoria = auditoria;
        this.diagnostico = diagnostico;
        this.pronostico = pronostico;
        this.memoria = memoria;
        this.validador = validador;
        registrarConsultas();
        registrarOperaciones();
        registrarInterfazYMemoria();
    }

    public Optional<Herramienta> buscar(String nombre) {
        return Optional.ofNullable(todas.get(nombre));
    }

    public Collection<Herramienta> todas() {
        return todas.values();
    }

    /** Definiciones en el formato de "tools" de Ollama (solo las que el rol puede usar). */
    public List<Map<String, Object>> definiciones(boolean esAdmin) {
        return todas.values().stream().filter(h -> esAdmin || !h.soloAdmin())
                .map(h -> Map.<String, Object>of("type", "function", "function", Map.of(
                        "name", h.nombre(),
                        "description", h.descripcion() + (h.escritura() ? " (pide confirmacion al usuario)" : ""),
                        "parameters", h.parametros())))
                .toList();
    }

    // =====================================================================================  CONSULTAS

    private void registrarConsultas() {
        consulta("resumen_general", "Resumen del estado del negocio: productos, stock bajo, alertas, dispositivos y ventas de hoy.",
                esquema(), (a, c) -> {
                    ResumenDashboard d = dashboard.resumen();
                    ResumenVentas v = ventas.resumen();
                    String t = "Hay " + d.productosActivos() + " productos activos, " + d.productosBajoMinimo()
                            + " bajo su stock mínimo, " + d.alertasActivas() + " alertas activas, "
                            + d.discrepanciasPendientes() + " discrepancias pendientes y " + d.dispositivosEnLinea() + " de "
                            + d.dispositivosTotal() + " dispositivos en línea. Hoy van " + v.ventasHoy() + " ventas por "
                            + soles(v.totalHoy()) + ".";
                    return new Resultado(t, Map.of("inventario", d, "ventas", v));
                });

        consulta("diagnosticar", "Problemas actuales del almacen con sugerencias de solucion (alertas, ventiladores).",
                esquema(), (a, c) -> {
                    DiagnosticoResponse d = diagnostico.diagnosticar();
                    String t = d.resumen() + (d.problemas().isEmpty() ? "" : " " + d.problemas().stream().limit(3)
                            .map(p -> p.titulo() + ": " + p.sugerencia()).collect(Collectors.joining(" ")));
                    return new Resultado(t, d);
                });

        consulta("buscar_productos", "Busca productos del catalogo por codigo o nombre. Devuelve precio, stock total y minimo.",
                esquema(opcional("texto", "string", "codigo o parte del nombre; vacio = todos"),
                        opcional("incluir_inactivos", "boolean", "incluir productos desactivados")),
                (a, c) -> {
                    var r = productos.buscar(a.texto("texto"), null, !a.booleano("incluir_inactivos", false), 0, 40);
                    if (r.contenido().isEmpty()) return Resultado.de("No encontré productos con «" + a.texto("texto") + "».");
                    String t = r.contenido().stream().limit(10).map(p -> p.codigo() + " " + p.nombre() + ": "
                            + soles(p.precio()) + ", stock " + p.stockTotal() + (p.activo() ? "" : " (inactivo)"))
                            .collect(Collectors.joining("; ")) + (r.totalElementos() > 10 ? "; y " + (r.totalElementos() - 10) + " más." : ".");
                    return new Resultado(t, r.contenido());
                });

        consulta("consultar_existencias", "Stock por producto y ubicacion. Con solo_bajo_minimo lista los productos por debajo de su minimo.",
                esquema(opcional("producto", "string", "codigo o nombre del producto"),
                        opcional("ubicacion", "string", "nombre de la ubicacion o almacen"),
                        opcional("solo_bajo_minimo", "boolean", "solo productos bajo su stock minimo")),
                (a, c) -> {
                    if (a.booleano("solo_bajo_minimo", false)) {
                        List<ProductoResponse> bajos = todosLosProductos().stream()
                                .filter(p -> p.stockTotal() < p.stockMinimo()).toList();
                        if (bajos.isEmpty()) return Resultado.de("Ningún producto está por debajo de su stock mínimo.");
                        return new Resultado("Bajo el mínimo: " + bajos.stream().map(p -> p.nombre() + " (" + p.stockTotal()
                                + " de " + p.stockMinimo() + ")").collect(Collectors.joining(", ")) + ".", bajos);
                    }
                    Long pid = a.tiene("producto") ? producto(a.texto("producto")).id() : null;
                    Long uid = a.tiene("ubicacion") ? ubicacion(a.texto("ubicacion")).id() : null;
                    List<InventarioResponse> l = movimientos.existencias(pid, uid);
                    if (l.isEmpty()) return Resultado.de("No hay existencias registradas para esa consulta.");
                    String t = l.stream().limit(15).map(i -> i.productoNombre() + " en " + i.ubicacion() + ": " + i.cantidad())
                            .collect(Collectors.joining("; ")) + ".";
                    return new Resultado(t, l);
                });

        consulta("consultar_movimientos", "Historial de entradas, salidas, transferencias y ajustes.",
                esquema(opcional("producto", "string", "codigo o nombre"), opcional("usuario", "string", "usuario responsable"),
                        opcional("dias", "integer", "dias hacia atras, por defecto 7")),
                (a, c) -> {
                    Long pid = a.tiene("producto") ? producto(a.texto("producto")).id() : null;
                    int dias = a.entero("dias", 7);
                    var r = movimientos.historial(pid, a.texto("usuario"), desdeDias(dias), manana(), 0, 30);
                    if (r.contenido().isEmpty()) return Resultado.de("No hay movimientos en los últimos " + dias + " días.");
                    String t = r.totalElementos() + " movimientos en " + dias + " días. Últimos: " + r.contenido().stream().limit(5)
                            .map(m -> m.tipo() + " " + m.cantidad() + " " + m.producto() + " (" + m.usuario() + ")")
                            .collect(Collectors.joining("; ")) + ".";
                    return new Resultado(t, r.contenido());
                });

        consulta("resumen_ventas", "Ventas de hoy y del mes, ticket promedio y productos mas vendidos.",
                esquema(), (a, c) -> {
                    ResumenVentas r = ventas.resumen();
                    String t = "Hoy: " + r.ventasHoy() + (r.ventasHoy() == 1 ? " venta por " : " ventas por ") + soles(r.totalHoy()) + " (ticket promedio "
                            + soles(r.ticketPromedioHoy()) + "). Mes: " + r.ventasMes() + (r.ventasMes() == 1 ? " venta por " : " ventas por ") + soles(r.totalMes()) + "."
                            + (r.masVendidosMes().isEmpty() ? "" : " Lo más vendido: " + r.masVendidosMes().get(0).nombre() + ".");
                    return new Resultado(t, r);
                });

        consulta("consultar_ventas", "Lista de ventas (comprobantes) recientes.",
                esquema(opcional("dias", "integer", "dias hacia atras, por defecto 7"),
                        enumeracion("estado", "EMITIDA o ANULADA", false, "EMITIDA", "ANULADA")),
                (a, c) -> {
                    int dias = a.entero("dias", 7);
                    EstadoVenta estado = a.tiene("estado") ? EstadoVenta.valueOf(a.texto("estado").toUpperCase()) : null;
                    var r = ventas.listar(estado, desdeDias(dias), manana(), 0, 30);
                    if (r.contenido().isEmpty()) return Resultado.de("No hay ventas en los últimos " + dias + " días.");
                    List<Map<String, Object>> datos = r.contenido().stream().map(v -> Map.<String, Object>of(
                            "id", v.id(), "numero", v.numero(), "fecha", v.fecha().toString(), "total", v.total(),
                            "estado", v.estado(), "metodoPago", v.metodoPago(), "vendedor", v.vendedor())).toList();
                    String t = r.totalElementos() + " ventas en " + dias + " días. Últimas: " + r.contenido().stream().limit(5)
                            .map(v -> v.numero() + " " + soles(v.total()) + (v.estado() == EstadoVenta.ANULADA ? " (anulada)" : ""))
                            .collect(Collectors.joining("; ")) + ".";
                    return new Resultado(t, datos);
                });

        consulta("consultar_alertas", "Alertas activas (temperatura, humedad, stock minimo, discrepancias, dispositivos).",
                esquema(), (a, c) -> {
                    List<AlertaResponse> l = alertas.activasRecientes();
                    if (l.isEmpty()) return Resultado.de("No hay alertas activas.");
                    return new Resultado(l.size() + " alertas activas: " + l.stream().limit(5)
                            .map(x -> "[" + x.id() + "] " + x.mensaje()).collect(Collectors.joining("; ")) + ".", l);
                });

        consulta("consultar_discrepancias", "Discrepancias pendientes entre el stock registrado y el estimado por peso.",
                esquema(), (a, c) -> {
                    List<DiscrepanciaResponse> l = estimacion.listarDiscrepancias(true);
                    if (l.isEmpty()) return Resultado.de("No hay discrepancias pendientes.");
                    return new Resultado(l.size() + " discrepancias pendientes: " + l.stream().map(d -> "[" + d.id() + "] estimado "
                            + d.estimacion().cantidadEstimada() + " vs registrado " + d.estimacion().cantidadRegistrada())
                            .collect(Collectors.joining("; ")) + ".", l);
                });

        consulta("estado_dispositivos", "Dispositivos ESP32: conexion, ventilador y ultimas lecturas de sus sensores (temperatura, humedad, peso).",
                esquema(), (a, c) -> {
                    List<DispositivoResponse> l = dispositivos.listar();
                    List<UltimaLectura> lecturas = iot.ultimasLecturas();
                    if (l.isEmpty()) return Resultado.de("No hay dispositivos registrados.");
                    String t = l.stream().map(d -> d.codigo() + " (" + d.ubicacion() + ") " + (d.enLinea() ? "en línea" : "sin conexión")
                            + ", ventilador " + (d.ventiladorEncendido() ? "encendido" : "apagado") + " en modo " + d.ventiladorModo())
                            .collect(Collectors.joining("; ")) + ". " + lecturas.stream()
                            .map(x -> x.tipo() + " " + x.valor().stripTrailingZeros().toPlainString()).collect(Collectors.joining(", "));
                    return new Resultado(t.trim(), Map.of("dispositivos", l, "ultimasLecturas", lecturas,
                            "ubicaciones", catalogo.listarUbicaciones()));
                });

        consulta("pronosticar_agotamiento", "Prediccion aprendida de las ventas y salidas reales: demanda diaria, dias hasta agotarse y cuanto reponer.",
                esquema(opcional("producto", "string", "codigo o nombre; vacio = los mas urgentes")),
                (a, c) -> {
                    List<PronosticoService.Pronostico> l = pronostico.calcular();
                    if (a.tiene("producto")) {
                        Long pid = producto(a.texto("producto")).id();
                        l = l.stream().filter(p -> p.productoId().equals(pid)).toList();
                    }
                    List<PronosticoService.Pronostico> conDatos = l.stream().filter(p -> p.diasRestantes() != null).toList();
                    if (conDatos.isEmpty()) {
                        return new Resultado("Todavía no hay salidas suficientes para predecir. El pronóstico aprende con cada venta o salida.", l);
                    }
                    String t = conDatos.stream().limit(5).map(p -> p.nombre() + ": vende " + p.demandaDiaria().stripTrailingZeros().toPlainString()
                            + " por día, alcanza " + p.diasRestantes() + " días" + (p.sugeridoReponer() > 0 ? ", conviene reponer "
                            + p.sugeridoReponer() : "") + " (confianza " + p.confianza().toLowerCase() + ")")
                            .collect(Collectors.joining("; ")) + ".";
                    return new Resultado(t, l.stream().limit(20).toList());
                });

        consulta("listar_catalogos", "Ubicaciones (almacenes) y categorias disponibles, con sus ids.",
                esquema(), (a, c) -> new Resultado(
                        "Ubicaciones: " + catalogo.listarUbicaciones().stream().map(UbicacionResponse::nombre).collect(Collectors.joining(", "))
                                + ". Categorías: " + catalogo.listarCategorias().stream().map(CategoriaResponse::nombre).collect(Collectors.joining(", ")) + ".",
                        Map.of("ubicaciones", catalogo.listarUbicaciones(), "categorias", catalogo.listarCategorias())));

        Herramienta auditoriaH = new Herramienta("consultar_auditoria", "Ultimas acciones registradas en la auditoria (quien hizo que).",
                esquema(), false, true, null, (a, c) -> {
                    var l = auditoria.ultimos().stream().limit(20).toList();
                    return new Resultado(l.stream().limit(5).map(x -> x.usuario() + ": " + x.accion() + " " + Objects.toString(x.detalle(), ""))
                            .collect(Collectors.joining("; ")), l);
                });
        todas.put(auditoriaH.nombre(), auditoriaH);
    }

    // =====================================================================================  OPERACIONES

    private void registrarOperaciones() {
        operacion("registrar_movimiento", "Registra una ENTRADA, SALIDA, TRANSFERENCIA o AJUSTE (ajustes solo ADMIN) de stock.",
                esquema(requerido(enumeracion("tipo", "tipo de movimiento", true, "ENTRADA", "SALIDA", "TRANSFERENCIA",
                                "AJUSTE_POSITIVO", "AJUSTE_NEGATIVO")),
                        requerido(propiedad("producto", "string", "codigo o nombre del producto")),
                        requerido(propiedad("cantidad", "integer", "unidades, mayor que cero")),
                        opcional("motivo", "string", "motivo del movimiento"),
                        opcional("ubicacion_origen", "string", "de donde sale (SALIDA, TRANSFERENCIA, AJUSTE_NEGATIVO)"),
                        opcional("ubicacion_destino", "string", "a donde entra (ENTRADA, TRANSFERENCIA, AJUSTE_POSITIVO)")),
                false,
                (a, c) -> {
                    MovimientoRequest r = movimiento(a);
                    ProductoResponse p = productos.obtener(r.productoId());
                    return r.tipo() + " de " + r.cantidad() + " " + p.unidad() + " de " + p.nombre()
                            + (r.ubicacionOrigenId() != null ? " desde " + nombreUbicacion(r.ubicacionOrigenId()) : "")
                            + (r.ubicacionDestinoId() != null ? " hacia " + nombreUbicacion(r.ubicacionDestinoId()) : "")
                            + ". Motivo: " + r.motivo();
                },
                (a, c) -> {
                    MovimientoResponse m = movimientos.registrar(movimiento(a), c.usuario(), c.esAdmin());
                    return new Resultado("Registré la " + m.tipo().name().toLowerCase().replace('_', ' ') + " de " + m.cantidad()
                            + " " + m.producto() + ".", m);
                });

        operacion("registrar_venta", "Registra una venta (descuenta stock y emite comprobante). El precio lo pone el sistema.",
                esquema(requerido(Map.of("name", "productos", "schema", Map.of("type", "array",
                                "description", "productos vendidos",
                                "items", Map.of("type", "object", "properties", Map.of(
                                        "producto", Map.of("type", "string", "description", "codigo o nombre"),
                                        "cantidad", Map.of("type", "integer")), "required", List.of("producto", "cantidad"))))),
                        opcional("ubicacion", "string", "almacen desde el que se vende; por defecto el primero activo"),
                        enumeracion("metodo_pago", "por defecto EFECTIVO", false, "EFECTIVO", "TARJETA", "YAPE", "PLIN", "TRANSFERENCIA"),
                        enumeracion("comprobante", "por defecto BOLETA", false, "BOLETA", "FACTURA", "NOTA_VENTA"),
                        opcional("cliente_documento", "string", "DNI (8) o RUC (11 digitos)"),
                        opcional("cliente_nombre", "string", "nombre o razon social"),
                        opcional("monto_recibido", "number", "efectivo recibido para calcular el vuelto")),
                false,
                (a, c) -> {
                    VentaRequest r = venta(a);
                    BigDecimal total = BigDecimal.ZERO;
                    List<String> lineas = new ArrayList<>();
                    for (VentaItemRequest i : r.items()) {
                        ProductoResponse p = productos.obtener(i.productoId());
                        total = total.add(p.precio().multiply(BigDecimal.valueOf(i.cantidad())));
                        lineas.add(i.cantidad() + " " + p.nombre());
                    }
                    return r.tipoComprobante() + " por " + String.join(", ", lineas) + " = " + soles(total) + ", pago "
                            + r.metodoPago() + " desde " + nombreUbicacion(r.ubicacionId());
                },
                (a, c) -> {
                    VentaResponse v = ventas.registrar(venta(a), c.usuario());
                    c.navegar = "/ventas.html";
                    return new Resultado("Venta " + v.numero() + " registrada por " + soles(v.total())
                            + (v.vuelto() != null && v.vuelto().signum() > 0 ? ". Vuelto: " + soles(v.vuelto()) : "") + ".", v);
                });

        operacion("anular_venta", "Anula una venta y devuelve el stock (solo ADMIN).",
                esquema(requerido(propiedad("venta", "string", "numero (B001-00000001) o id de la venta")),
                        requerido(propiedad("motivo", "string", "motivo de la anulacion"))),
                true,
                (a, c) -> {
                    VentaResponse v = venta(a.texto("venta"));
                    return "Anular la venta " + v.numero() + " de " + soles(v.total()) + ". Motivo: " + a.requerido("motivo");
                },
                (a, c) -> {
                    VentaResponse v = ventas.anular(venta(a.texto("venta")).id(), a.requerido("motivo"), c.usuario());
                    return new Resultado("Anulé la venta " + v.numero() + "; el stock volvió a " + v.ubicacion() + ".", v);
                });

        operacion("crear_producto", "Crea un producto en el catalogo (solo ADMIN).",
                esquema(requerido(propiedad("codigo", "string", "codigo unico, letras/numeros/-/_")),
                        requerido(propiedad("nombre", "string", "nombre")),
                        requerido(propiedad("categoria", "string", "nombre de la categoria")),
                        requerido(propiedad("precio", "number", "precio de venta con IGV")),
                        opcional("unidad", "string", "unidad de medida, por defecto unidad"),
                        opcional("stock_minimo", "integer", "por defecto 0"),
                        opcional("peso_unitario_g", "number", "peso de una unidad en gramos (para la balanza)"),
                        opcional("descripcion", "string", "descripcion")),
                true,
                (a, c) -> {
                    ProductoRequest r = productoNuevo(a);
                    return "Crear el producto " + r.codigo() + " " + r.nombre() + " a " + soles(r.precio())
                            + ", stock mínimo " + r.stockMinimo();
                },
                (a, c) -> {
                    ProductoResponse p = productos.crear(productoNuevo(a));
                    return new Resultado("Creé el producto " + p.codigo() + " " + p.nombre() + ".", p);
                });

        operacion("editar_producto", "Cambia precio, nombre, stock minimo o peso unitario de un producto (solo ADMIN).",
                esquema(requerido(propiedad("producto", "string", "codigo o nombre actual")),
                        opcional("precio", "number", "nuevo precio"), opcional("nombre", "string", "nuevo nombre"),
                        opcional("stock_minimo", "integer", "nuevo stock minimo"),
                        opcional("peso_unitario_g", "number", "nuevo peso unitario en gramos")),
                true,
                (a, c) -> {
                    ProductoResponse p = producto(a.requerido("producto"));
                    ProductoRequest r = productoEditado(p, a);
                    List<String> cambios = new ArrayList<>();
                    if (!Objects.equals(r.nombre(), p.nombre())) cambios.add("nombre a «" + r.nombre() + "»");
                    if (r.precio().compareTo(p.precio()) != 0) cambios.add("precio de " + soles(p.precio()) + " a " + soles(r.precio()));
                    if (!Objects.equals(r.stockMinimo(), p.stockMinimo())) cambios.add("stock mínimo a " + r.stockMinimo());
                    if (!Objects.equals(r.pesoUnitarioG(), p.pesoUnitarioG())) cambios.add("peso unitario a " + r.pesoUnitarioG() + " g");
                    if (cambios.isEmpty()) throw NegocioException.invalido("No indicaste ningún cambio para " + p.nombre());
                    return "Cambiar en " + p.codigo() + " " + p.nombre() + ": " + String.join(", ", cambios);
                },
                (a, c) -> {
                    ProductoResponse p = producto(a.requerido("producto"));
                    ProductoResponse e = productos.editar(p.id(), productoEditado(p, a));
                    return new Resultado("Actualicé " + e.codigo() + " " + e.nombre() + ".", e);
                });

        operacion("activar_producto", "Activa o desactiva un producto (desactivacion logica, solo ADMIN).",
                esquema(requerido(propiedad("producto", "string", "codigo o nombre")),
                        requerido(propiedad("activo", "boolean", "true para activar, false para desactivar"))),
                true,
                (a, c) -> (a.booleano("activo", true) ? "Activar " : "Desactivar ") + producto(a.requerido("producto")).nombre(),
                (a, c) -> {
                    ProductoResponse p = productos.cambiarEstado(producto(a.requerido("producto")).id(), a.booleano("activo", true));
                    return new Resultado(p.nombre() + (p.activo() ? " quedó activo." : " quedó desactivado."), p);
                });

        operacion("reconocer_alertas", "Marca alertas como vistas (siguen activas hasta normalizarse).",
                esquema(Map.of("name", "ids", "schema", Map.of("type", "array", "items", Map.of("type", "integer"),
                        "description", "ids de alertas; vacio = todas las abiertas"))),
                false,
                (a, c) -> {
                    List<Long> ids = idsAlertas(a);
                    if (ids.isEmpty()) throw NegocioException.invalido("No hay alertas nuevas por marcar como vistas");
                    return "Marcar como vistas " + ids.size() + (ids.size() == 1 ? " alerta" : " alertas");
                },
                (a, c) -> {
                    List<Long> ids = idsAlertas(a);
                    ids.forEach(id -> alertas.reconocer(id, c.usuario()));
                    return Resultado.de("Marqué " + ids.size() + (ids.size() == 1 ? " alerta como vista." : " alertas como vistas."));
                });

        operacion("controlar_ventilador", "Enciende, apaga o pone en automatico el ventilador de un ESP32 (solo ADMIN).",
                esquema(requerido(enumeracion("modo", "modo del ventilador", true, "ENCENDIDO", "APAGADO", "AUTO")),
                        opcional("dispositivo", "string", "codigo del ESP32 o ubicacion; vacio = todos")),
                true,
                (a, c) -> "Poner el ventilador de " + dispositivosElegidos(a).stream().map(DispositivoResponse::codigo)
                        .collect(Collectors.joining(", ")) + " en " + a.requerido("modo").toUpperCase(),
                (a, c) -> {
                    ModoVentilador modo = ModoVentilador.valueOf(a.requerido("modo").toUpperCase());
                    List<String> hechos = dispositivosElegidos(a).stream()
                            .map(d -> ventilador.cambiarModo(d.id(), modo).codigo()).toList();
                    return Resultado.de("Ventilador de " + String.join(", ", hechos) + " en " + modo
                            + ". El ESP32 recibe la orden en unos segundos.");
                });

        operacion("resolver_discrepancia", "Aprueba (ajusta el stock al peso medido) o rechaza una discrepancia (solo ADMIN).",
                esquema(requerido(propiedad("id", "integer", "id de la discrepancia")),
                        requerido(propiedad("aprobar", "boolean", "true aprueba, false rechaza")),
                        requerido(propiedad("comentario", "string", "justificacion"))),
                true,
                (a, c) -> (a.booleano("aprobar", false) ? "Aprobar" : "Rechazar") + " la discrepancia " + a.entero("id", 0)
                        + ". Comentario: " + a.requerido("comentario"),
                (a, c) -> {
                    Long id = (long) a.entero("id", 0);
                    DiscrepanciaResponse d = a.booleano("aprobar", false)
                            ? estimacion.aprobar(id, c.usuario(), a.requerido("comentario"))
                            : estimacion.rechazar(id, c.usuario(), a.requerido("comentario"));
                    return new Resultado("Discrepancia " + d.id() + " " + d.estado().name().toLowerCase() + ".", d);
                });
    }

    // =====================================================================================  INTERFAZ Y MEMORIA

    private void registrarInterfazYMemoria() {
        consulta("abrir_pantalla", "Abre una pantalla del programa.",
                esquema(requerido(enumeracion("pantalla", "pantalla a abrir", true, PANTALLAS.keySet().toArray(String[]::new)))),
                (a, c) -> {
                    String p = a.requerido("pantalla").toLowerCase();
                    c.navegar = PANTALLAS.get(p);
                    if (c.navegar == null) throw NegocioException.invalido("No existe la pantalla " + p);
                    return Resultado.de("Abriendo " + p + ".");
                });

        Herramienta reporte = new Herramienta("descargar_reporte", "Descarga un reporte CSV (se abre en Excel). Solo ADMIN.",
                esquema(requerido(enumeracion("tipo", "reporte", true, "existencias", "movimientos", "ventas", "alertas", "discrepancias")),
                        opcional("dias", "integer", "dias hacia atras, por defecto 30")),
                false, true, null, (a, c) -> {
                    String tipo = a.requerido("tipo").toLowerCase();
                    LocalDate hoy = LocalDate.now(ZONA);
                    c.descargar = "/api/reportes/" + tipo + ".csv?desde=" + hoy.minusDays(a.entero("dias", 30)) + "&hasta=" + hoy;
                    return Resultado.de("Descargando el reporte de " + tipo + ".");
                });
        todas.put(reporte.nombre(), reporte);

        consulta("imprimir_pantalla", "Imprime la pantalla actual (en ventas, el comprobante abierto).",
                esquema(), (a, c) -> {
                    c.imprimir = true;
                    return Resultado.de("Abriendo el diálogo de impresión.");
                });

        consulta("recordar", "Guarda algo que el usuario quiere que recuerdes para siempre (datos del negocio o preferencias).",
                esquema(requerido(propiedad("texto", "string", "lo que hay que recordar, redactado como un hecho")),
                        enumeracion("tipo", "HECHO (dato) o PREFERENCIA (como responder)", false, "HECHO", "PREFERENCIA")),
                (a, c) -> {
                    MemoriaIa.Tipo tipo = "PREFERENCIA".equalsIgnoreCase(a.texto("tipo")) ? MemoriaIa.Tipo.PREFERENCIA : MemoriaIa.Tipo.HECHO;
                    MemoriaIa.Recuerdo r = memoria.recordar(tipo, a.requerido("texto"), c.usuario());
                    return new Resultado("Aprendido: " + r.texto(), Map.of("id", r.id()));
                });

        consulta("olvidar", "Borra un recuerdo por su id.",
                esquema(requerido(propiedad("id", "integer", "id del recuerdo"))),
                (a, c) -> {
                    memoria.olvidar((long) a.entero("id", 0), c.usuario(), c.esAdmin());
                    return Resultado.de("Listo, lo olvidé.");
                });
    }

    // =====================================================================================  REGISTRO

    private void consulta(String nombre, String descripcion, Map<String, Object> parametros,
                          BiFunction<Args, Contexto, Resultado> ejecutar) {
        todas.put(nombre, new Herramienta(nombre, descripcion, parametros, false, false, null, ejecutar));
    }

    private void operacion(String nombre, String descripcion, Map<String, Object> parametros, boolean soloAdmin,
                           BiFunction<Args, Contexto, String> describir, BiFunction<Args, Contexto, Resultado> ejecutar) {
        todas.put(nombre, new Herramienta(nombre, descripcion, parametros, true, soloAdmin, describir, ejecutar));
    }

    // =====================================================================================  ESQUEMAS JSON

    @SafeVarargs
    private static Map<String, Object> esquema(Map<String, Object>... propiedades) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> requeridos = new ArrayList<>();
        for (Map<String, Object> p : propiedades) {
            props.put((String) p.get("name"), p.get("schema"));
            if (Boolean.TRUE.equals(p.get("required"))) requeridos.add((String) p.get("name"));
        }
        return Map.of("type", "object", "properties", props, "required", requeridos);
    }

    private static Map<String, Object> propiedad(String nombre, String tipo, String descripcion) {
        return Map.of("name", nombre, "schema", Map.of("type", tipo, "description", descripcion));
    }

    private static Map<String, Object> opcional(String nombre, String tipo, String descripcion) {
        return propiedad(nombre, tipo, descripcion);
    }

    private static Map<String, Object> requerido(Map<String, Object> p) {
        Map<String, Object> r = new HashMap<>(p);
        r.put("required", true);
        return r;
    }

    private static Map<String, Object> enumeracion(String nombre, String descripcion, boolean requerido, String... valores) {
        Map<String, Object> r = new HashMap<>(Map.of("name", nombre,
                "schema", Map.of("type", "string", "description", descripcion, "enum", List.of(valores))));
        if (requerido) r.put("required", true);
        return r;
    }

    // =====================================================================================  RESOLUCION DE NOMBRES

    /** Producto por id, codigo exacto o nombre aproximado (tolera errores de dictado). */
    ProductoResponse producto(String referencia) {
        if (referencia == null || referencia.isBlank()) throw NegocioException.invalido("Indica el producto");
        String ref = referencia.trim();
        List<ProductoResponse> todos = todosLosProductos();
        if (ref.matches("\\d+")) {
            Optional<ProductoResponse> porId = todos.stream().filter(p -> p.id().equals(Long.valueOf(ref))).findFirst();
            if (porId.isPresent()) return porId.get();
        }
        Optional<ProductoResponse> porCodigo = todos.stream().filter(p -> p.codigo().equalsIgnoreCase(ref)).findFirst();
        if (porCodigo.isPresent()) return porCodigo.get();
        ProductoResponse mejor = null;
        double puntaje = 0;
        for (ProductoResponse p : todos) {
            double s = Math.max(Texto.parecido(ref, p.nombre()), Texto.parecido(p.nombre(), ref) * 0.9);
            if (Texto.normalizar(ref).contains(Texto.normalizar(p.codigo()))) s = Math.max(s, 0.95);
            if (p.activo()) s += 0.01;
            if (s > puntaje) {
                puntaje = s;
                mejor = p;
            }
        }
        if (mejor == null || puntaje < 0.5) throw NegocioException.invalido("No encontré el producto «" + ref + "»");
        return mejor;
    }

    /** Producto mencionado dentro de una frase libre (para el motor sin modelo de lenguaje). */
    Optional<ProductoResponse> productoEnFrase(String frase) {
        ProductoResponse mejor = null;
        double puntaje = 0;
        String f = " " + Texto.normalizar(frase) + " ";
        for (ProductoResponse p : todosLosProductos()) {
            double s = f.contains(" " + Texto.normalizar(p.codigo()) + " ") ? 1.0 : Texto.parecido(frase, p.nombre());
            if (s > puntaje) {
                puntaje = s;
                mejor = p;
            }
        }
        return puntaje >= 0.5 ? Optional.of(mejor) : Optional.empty();
    }

    UbicacionResponse ubicacion(String referencia) {
        List<UbicacionResponse> activas = catalogo.listarUbicaciones().stream().filter(UbicacionResponse::activo).toList();
        if (activas.isEmpty()) throw NegocioException.invalido("No hay ubicaciones activas");
        if (referencia == null || referencia.isBlank()) return activas.get(0);
        String ref = referencia.trim();
        if (ref.matches("\\d+")) {
            Optional<UbicacionResponse> u = activas.stream().filter(x -> x.id().equals(Long.valueOf(ref))).findFirst();
            if (u.isPresent()) return u.get();
        }
        return activas.stream().max(Comparator.comparingDouble(u -> Texto.parecido(ref, u.nombre())
                        + (Texto.normalizar(u.nombre()).equals(Texto.normalizar(ref)) ? 1 : 0)))
                .filter(u -> Texto.parecido(ref, u.nombre()) >= 0.5 || Texto.normalizar(ref).endsWith(ultimaPalabra(u.nombre())))
                .orElseThrow(() -> NegocioException.invalido("No encontré la ubicación «" + ref + "»"));
    }

    Optional<UbicacionResponse> ubicacionEnFrase(String frase) {
        String f = Texto.normalizar(frase);
        return ubicacionesActivas().stream().filter(u -> f.contains(Texto.normalizar(u.nombre()))).findFirst();
    }

    List<UbicacionResponse> ubicacionesActivas() {
        return catalogo.listarUbicaciones().stream().filter(UbicacionResponse::activo).toList();
    }

    private static String ultimaPalabra(String s) {
        String[] p = Texto.normalizar(s).split(" ");
        return " " + p[p.length - 1];
    }

    private String nombreUbicacion(Long id) {
        return catalogo.listarUbicaciones().stream().filter(u -> u.id().equals(id)).map(UbicacionResponse::nombre)
                .findFirst().orElse("ubicación " + id);
    }

    List<ProductoResponse> todosLosProductos() {
        List<ProductoResponse> r = new ArrayList<>();
        for (int pagina = 0; ; pagina++) {
            var p = productos.buscar("", null, false, pagina, 100);
            r.addAll(p.contenido());
            if (pagina + 1 >= p.totalPaginas()) return r;
        }
    }

    private VentaResponse venta(String referencia) {
        if (referencia == null || referencia.isBlank()) throw NegocioException.invalido("Indica la venta");
        String ref = referencia.trim().toUpperCase();
        if (ref.matches("\\d+")) return ventas.obtener(Long.valueOf(ref));
        // "B001-00000007", "b001-7" o "B001 7" -> B001-00000007
        var m = java.util.regex.Pattern.compile("([A-Z]{1,2}\\d{2,3})\\s*-?\\s*0*(\\d{1,8})").matcher(ref);
        if (!m.find()) throw NegocioException.noEncontrado("Venta", referencia);
        String numero = m.group(1) + "-" + String.format("%08d", Integer.parseInt(m.group(2)));
        return ventas.listar(null, desdeDias(3650), manana(), 0, 500).contenido().stream()
                .filter(v -> v.numero().equals(numero))
                .findFirst().orElseThrow(() -> NegocioException.noEncontrado("Venta", numero));
    }

    private List<DispositivoResponse> dispositivosElegidos(Args a) {
        List<DispositivoResponse> activos = dispositivos.listar().stream().filter(DispositivoResponse::activo).toList();
        if (activos.isEmpty()) throw NegocioException.invalido("No hay dispositivos activos");
        if (!a.tiene("dispositivo")) return activos;
        String ref = Texto.normalizar(a.texto("dispositivo"));
        List<DispositivoResponse> r = activos.stream().filter(d -> Texto.normalizar(d.codigo()).equals(ref)
                || Texto.normalizar(d.codigo()).contains(ref) || Texto.parecido(ref, d.ubicacion()) >= 0.5).toList();
        if (r.isEmpty()) throw NegocioException.invalido("No encontré el dispositivo «" + a.texto("dispositivo") + "»");
        return r;
    }

    private List<Long> idsAlertas(Args a) {
        List<Long> ids = a.enteros("ids");
        if (!ids.isEmpty()) return ids;
        return alertas.activasRecientes().stream().filter(x -> x.estado() == EstadoAlerta.ABIERTA).map(AlertaResponse::id).toList();
    }

    // =====================================================================================  PETICIONES VALIDADAS

    private MovimientoRequest movimiento(Args a) {
        TipoMovimiento tipo = TipoMovimiento.valueOf(a.requerido("tipo").toUpperCase());
        ProductoResponse p = producto(a.requerido("producto"));
        Long origen = tipo.restaEnOrigen() ? ubicacion(a.texto("ubicacion_origen")).id() : null;
        Long destino = tipo.sumaEnDestino() ? ubicacion(a.texto("ubicacion_destino")).id() : null;
        if (tipo == TipoMovimiento.TRANSFERENCIA && Objects.equals(origen, destino)) {
            throw NegocioException.invalido("La transferencia necesita una ubicación de origen y otra de destino distintas");
        }
        String motivo = a.tiene("motivo") ? a.texto("motivo") : "Registrado con el asistente de IA";
        return validar(new MovimientoRequest(tipo, p.id(), origen, destino, a.entero("cantidad", 0), motivo));
    }

    private VentaRequest venta(Args a) {
        List<VentaItemRequest> items = new ArrayList<>();
        for (Map<String, Object> i : a.objetos("productos")) {
            Args x = new Args(i);
            items.add(new VentaItemRequest(producto(x.requerido("producto")).id(), x.entero("cantidad", 0)));
        }
        if (items.isEmpty()) throw NegocioException.invalido("Indica qué productos se venden");
        TipoComprobante tipo = a.tiene("comprobante") ? TipoComprobante.valueOf(a.texto("comprobante").toUpperCase()) : TipoComprobante.BOLETA;
        MetodoPago pago = a.tiene("metodo_pago") ? MetodoPago.valueOf(a.texto("metodo_pago").toUpperCase()) : MetodoPago.EFECTIVO;
        // El modelo envia 0 cuando no sabe cuanto pago el cliente: equivale a "monto exacto"
        BigDecimal recibido = a.decimal("monto_recibido");
        if (recibido != null && recibido.signum() <= 0) recibido = null;
        return validar(new VentaRequest(tipo, ubicacion(a.texto("ubicacion")).id(), a.texto("cliente_documento"),
                a.texto("cliente_nombre"), pago, recibido, items));
    }

    private ProductoRequest productoNuevo(Args a) {
        String cat = a.requerido("categoria");
        CategoriaResponse categoria = catalogo.listarCategorias().stream()
                .max(Comparator.comparingDouble(x -> Texto.parecido(cat, x.nombre())))
                .filter(x -> Texto.parecido(cat, x.nombre()) >= 0.5)
                .orElseThrow(() -> NegocioException.invalido("No existe la categoría «" + cat + "»"));
        return validar(new ProductoRequest(a.requerido("codigo").toUpperCase(), a.requerido("nombre"), a.texto("descripcion"),
                categoria.id(), a.tiene("unidad") ? a.texto("unidad") : "unidad", a.decimal("precio"),
                a.decimal("peso_unitario_g"), a.entero("stock_minimo", 0)));
    }

    private ProductoRequest productoEditado(ProductoResponse p, Args a) {
        return validar(new ProductoRequest(p.codigo(), a.tiene("nombre") ? a.texto("nombre") : p.nombre(), p.descripcion(),
                p.categoriaId(), p.unidad(), a.tiene("precio") ? a.decimal("precio") : p.precio(),
                a.tiene("peso_unitario_g") ? a.decimal("peso_unitario_g") : p.pesoUnitarioG(),
                a.tiene("stock_minimo") ? a.entero("stock_minimo", 0) : p.stockMinimo()));
    }

    /** Las mismas validaciones que la API REST aplica con @Valid. */
    private <T> T validar(T peticion) {
        Set<ConstraintViolation<T>> errores = validador.validate(peticion);
        if (!errores.isEmpty()) {
            throw NegocioException.invalido(errores.stream().map(e -> e.getPropertyPath() + " " + e.getMessage())
                    .sorted().collect(Collectors.joining("; ")));
        }
        return peticion;
    }

    // =====================================================================================  UTILIDADES

    static OffsetDateTime desdeDias(int dias) {
        return LocalDate.now(ZONA).minusDays(Math.max(dias, 0)).atStartOfDay(ZONA).toOffsetDateTime();
    }

    static OffsetDateTime manana() {
        return LocalDate.now(ZONA).plusDays(1).atStartOfDay(ZONA).toOffsetDateTime();
    }

    static String soles(BigDecimal v) {
        return "S/ " + (v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    /** Argumentos de una herramienta (llegan como JSON del modelo o armados por el motor local). */
    public static final class Args {
        private final Map<String, Object> m;

        public Args(Map<String, Object> m) {
            this.m = m == null ? Map.of() : m;
        }

        public Map<String, Object> mapa() { return m; }

        boolean tiene(String k) {
            Object v = m.get(k);
            return v != null && !(v instanceof String s && s.isBlank());
        }

        String texto(String k) {
            Object v = m.get(k);
            return v == null ? "" : String.valueOf(v).trim();
        }

        String requerido(String k) {
            if (!tiene(k)) throw NegocioException.invalido("Falta el dato «" + k.replace('_', ' ') + "»");
            return texto(k);
        }

        int entero(String k, int porDefecto) {
            Object v = m.get(k);
            if (v instanceof Number n) return n.intValue();
            try {
                return v == null || String.valueOf(v).isBlank() ? porDefecto : (int) Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e) {
                throw NegocioException.invalido("«" + v + "» no es un número válido para " + k);
            }
        }

        BigDecimal decimal(String k) {
            Object v = m.get(k);
            if (v == null || String.valueOf(v).isBlank()) return null;
            try {
                return new BigDecimal(String.valueOf(v).trim().replace(",", "."));
            } catch (NumberFormatException e) {
                throw NegocioException.invalido("«" + v + "» no es un número válido para " + k);
            }
        }

        boolean booleano(String k, boolean porDefecto) {
            Object v = m.get(k);
            if (v instanceof Boolean b) return b;
            if (v == null) return porDefecto;
            String s = Texto.normalizar(String.valueOf(v));
            return s.equals("true") || s.equals("si") || s.equals("1");
        }

        List<Long> enteros(String k) {
            Object v = m.get(k);
            if (!(v instanceof Collection<?> c)) return List.of();
            return c.stream().map(x -> Long.valueOf(String.valueOf(x).replaceAll("\\.0$", ""))).toList();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objetos(String k) {
            Object v = m.get(k);
            if (!(v instanceof Collection<?> c)) return List.of();
            return c.stream().filter(Map.class::isInstance).map(x -> (Map<String, Object>) x).toList();
        }
    }
}
