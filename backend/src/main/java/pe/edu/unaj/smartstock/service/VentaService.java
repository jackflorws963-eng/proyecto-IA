package pe.edu.unaj.smartstock.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoVenta;
import pe.edu.unaj.smartstock.entity.Enums.MetodoPago;
import pe.edu.unaj.smartstock.entity.Enums.TipoComprobante;
import pe.edu.unaj.smartstock.entity.Enums.TipoMovimiento;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VentaService {

    static final ZoneId ZONA = ZoneId.of("America/Lima");
    static final BigDecimal BOLETA_CON_DOCUMENTO = new BigDecimal("700");

    private final VentaRepository ventas;
    private final SerieComprobanteRepository series;
    private final ProductoRepository productos;
    private final UbicacionRepository ubicaciones;
    private final UsuarioRepository usuarios;
    private final MovimientoService movimientos;
    private final AuditoriaService auditoria;
    private final BigDecimal igvPct;

    public VentaService(VentaRepository ventas, SerieComprobanteRepository series, ProductoRepository productos,
                        UbicacionRepository ubicaciones, UsuarioRepository usuarios, MovimientoService movimientos,
                        AuditoriaService auditoria, @Value("${smartstock.ventas.igv-pct:18}") BigDecimal igvPct) {
        this.ventas = ventas;
        this.series = series;
        this.productos = productos;
        this.ubicaciones = ubicaciones;
        this.usuarios = usuarios;
        this.movimientos = movimientos;
        this.auditoria = auditoria;
        this.igvPct = igvPct;
    }

    @Transactional
    public VentaResponse registrar(VentaRequest r, String username) {
        Ubicacion ubicacion = ubicaciones.findById(r.ubicacionId())
                .orElseThrow(() -> NegocioException.noEncontrado("Ubicacion", r.ubicacionId()));
        if (!ubicacion.isActivo()) throw NegocioException.invalido("La ubicacion " + ubicacion.getNombre() + " esta desactivada");
        Usuario vendedor = usuarios.findByUsername(username)
                .orElseThrow(() -> NegocioException.noEncontrado("Usuario", username));

        Map<Long, Integer> cantidades = new LinkedHashMap<>();
        r.items().forEach(i -> cantidades.merge(i.productoId(), i.cantidad(), Integer::sum));

        Venta v = new Venta();
        BigDecimal total = BigDecimal.ZERO;
        for (var item : cantidades.entrySet()) {
            Producto p = productos.findById(item.getKey())
                    .orElseThrow(() -> NegocioException.noEncontrado("Producto", item.getKey()));
            if (!p.isActivo()) throw NegocioException.invalido("El producto " + p.getCodigo() + " esta desactivado");
            VentaDetalle d = new VentaDetalle();
            d.setProducto(p);
            d.setCantidad(item.getValue());
            d.setPrecioUnitario(p.getPrecio());
            d.setImporte(p.getPrecio().multiply(BigDecimal.valueOf(item.getValue())));
            total = total.add(d.getImporte());
            v.agregar(d);
        }
        if (total.signum() <= 0) throw NegocioException.invalido("El total de la venta debe ser mayor que cero");

        String documento = limpiar(r.clienteDocumento());
        String nombre = limpiar(r.clienteNombre());
        validarCliente(r.tipoComprobante(), documento, nombre, total);

        BigDecimal[] partes = desglosar(total, igvPct);
        v.setTipoComprobante(r.tipoComprobante());
        v.setUbicacion(ubicacion);
        v.setClienteDocumento(documento);
        v.setClienteNombre(nombre);
        v.setMetodoPago(r.metodoPago());
        v.setSubtotal(partes[0]);
        v.setIgv(partes[1]);
        v.setTotal(total);
        v.setUsuario(vendedor);
        if (r.metodoPago() == MetodoPago.EFECTIVO) {
            BigDecimal recibido = r.montoRecibido() == null ? total : r.montoRecibido().setScale(2, RoundingMode.HALF_UP);
            if (recibido.compareTo(total) < 0) {
                throw NegocioException.invalido("El monto recibido (S/ " + recibido + ") no cubre el total (S/ " + total + ")");
            }
            v.setMontoRecibido(recibido);
            v.setVuelto(recibido.subtract(total));
        }

        SerieComprobante serie = series.bloquear(r.tipoComprobante())
                .orElseThrow(() -> NegocioException.noEncontrado("Serie de comprobante", r.tipoComprobante()));
        serie.setUltimo(serie.getUltimo() + 1);
        v.setSerie(serie.getSerie());
        v.setCorrelativo(serie.getUltimo());

        for (VentaDetalle d : v.getDetalles()) {
            d.setMovimiento(movimientos.registrarInterno(new MovimientoRequest(TipoMovimiento.SALIDA,
                    d.getProducto().getId(), ubicacion.getId(), null, d.getCantidad(), "Venta " + v.getNumero()),
                    username, false, null));
        }
        v = ventas.save(v);
        auditoria.registrar("VENTA", "venta", v.getId(), v.getNumero() + " " + r.metodoPago() + " S/ " + total
                + (nombre == null ? "" : " | " + nombre));
        return VentaResponse.de(v);
    }

    @Transactional
    public VentaResponse anular(Long id, String motivo, String username) {
        Venta v = buscar(id);
        if (v.getEstado() != EstadoVenta.EMITIDA) throw NegocioException.conflicto("La venta " + v.getNumero() + " ya esta anulada");
        Usuario u = usuarios.findByUsername(username).orElseThrow(() -> NegocioException.noEncontrado("Usuario", username));
        for (VentaDetalle d : v.getDetalles()) {
            movimientos.registrarInterno(new MovimientoRequest(TipoMovimiento.ENTRADA, d.getProducto().getId(), null,
                    v.getUbicacion().getId(), d.getCantidad(), "Anulacion venta " + v.getNumero()), username, true, null);
        }
        v.setEstado(EstadoVenta.ANULADA);
        v.setAnuladaPor(u);
        v.setAnuladaEn(OffsetDateTime.now());
        v.setMotivoAnulacion(motivo.trim());
        auditoria.registrar("ANULAR_VENTA", "venta", v.getId(), v.getNumero() + " | " + motivo.trim());
        return VentaResponse.de(v);
    }

    @Transactional(readOnly = true)
    public VentaResponse obtener(Long id) {
        return VentaResponse.de(buscar(id));
    }

    @Transactional(readOnly = true)
    public PaginaResponse<VentaResponse> listar(EstadoVenta estado, OffsetDateTime desde, OffsetDateTime hasta,
                                                int pagina, int tamano) {
        Page<Venta> page = ventas.filtrar(estado, desde, hasta,
                PageRequest.of(Math.max(pagina, 0), Math.min(Math.max(tamano, 1), 500)));
        return new PaginaResponse<>(page.getContent().stream().map(VentaResponse::de).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ResumenVentas resumen() {
        LocalDate hoy = LocalDate.now(ZONA);
        OffsetDateTime inicioHoy = hoy.atStartOfDay(ZONA).toOffsetDateTime();
        OffsetDateTime inicioMes = hoy.withDayOfMonth(1).atStartOfDay(ZONA).toOffsetDateTime();
        OffsetDateTime manana = hoy.plusDays(1).atStartOfDay(ZONA).toOffsetDateTime();
        Object[] dia = ventas.totales(inicioHoy, manana).get(0);
        Object[] mes = ventas.totales(inicioMes, manana).get(0);
        long nDia = ((Number) dia[0]).longValue();
        BigDecimal totalDia = decimal(dia[1]);
        List<ProductoVendido> top = ventas.masVendidos(inicioMes, manana, PageRequest.of(0, 5)).stream()
                .map(f -> new ProductoVendido((String) f[0], (String) f[1], ((Number) f[2]).longValue(), decimal(f[3])))
                .toList();
        return new ResumenVentas(nDia, totalDia,
                nDia == 0 ? BigDecimal.ZERO : totalDia.divide(BigDecimal.valueOf(nDia), 2, RoundingMode.HALF_UP),
                ((Number) mes[0]).longValue(), decimal(mes[1]), top);
    }

    static BigDecimal[] desglosar(BigDecimal total, BigDecimal igvPct) {
        BigDecimal factor = BigDecimal.ONE.add(igvPct.movePointLeft(2));
        BigDecimal subtotal = total.divide(factor, 2, RoundingMode.HALF_UP);
        return new BigDecimal[]{subtotal, total.subtract(subtotal)};
    }

    static void validarCliente(TipoComprobante tipo, String documento, String nombre, BigDecimal total) {
        if (documento != null && !documento.matches("\\d{8}|\\d{11}")) {
            throw NegocioException.invalido("El documento del cliente debe ser un DNI (8 digitos) o RUC (11 digitos)");
        }
        if (tipo == TipoComprobante.FACTURA && (documento == null || documento.length() != 11 || nombre == null)) {
            throw NegocioException.invalido("La factura requiere el RUC (11 digitos) y la razon social del cliente");
        }
        if (tipo == TipoComprobante.BOLETA && total.compareTo(BOLETA_CON_DOCUMENTO) >= 0 && documento == null) {
            throw NegocioException.invalido("Una boleta de S/ " + BOLETA_CON_DOCUMENTO + " o mas requiere el DNI o RUC del cliente");
        }
    }

    private Venta buscar(Long id) {
        return ventas.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Venta", id));
    }

    private static BigDecimal decimal(Object n) {
        return n == null ? BigDecimal.ZERO : new BigDecimal(n.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    private static String limpiar(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
