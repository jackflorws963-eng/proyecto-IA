package pe.edu.unaj.smartstock.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.InventarioResponse;
import pe.edu.unaj.smartstock.dto.Dtos.MovimientoRequest;
import pe.edu.unaj.smartstock.dto.Dtos.MovimientoResponse;
import pe.edu.unaj.smartstock.dto.Dtos.PaginaResponse;
import pe.edu.unaj.smartstock.entity.*;
import pe.edu.unaj.smartstock.entity.Enums.TipoMovimiento;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.*;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class MovimientoService {

    private final MovimientoRepository movimientos;
    private final InventarioRepository inventario;
    private final ProductoRepository productos;
    private final UbicacionRepository ubicaciones;
    private final UsuarioRepository usuarios;
    private final AuditoriaService auditoria;
    private final ReglasStockService reglasStock;

    public MovimientoService(MovimientoRepository movimientos, InventarioRepository inventario,
                             ProductoRepository productos, UbicacionRepository ubicaciones,
                             UsuarioRepository usuarios, AuditoriaService auditoria,
                             ReglasStockService reglasStock) {
        this.movimientos = movimientos;
        this.inventario = inventario;
        this.productos = productos;
        this.ubicaciones = ubicaciones;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
        this.reglasStock = reglasStock;
    }

    @Transactional
    public MovimientoResponse registrar(MovimientoRequest r, String username, boolean esAdmin) {
        return MovimientoResponse.de(registrarInterno(r, username, esAdmin, null));
    }

    @Transactional
    public Movimiento registrarInterno(MovimientoRequest r, String username, boolean esAdmin, Discrepancia discrepancia) {
        TipoMovimiento tipo = r.tipo();
        if (tipo.esAjuste() && !esAdmin) {
            throw new NegocioException(HttpStatus.FORBIDDEN, "Solo un ADMIN puede registrar ajustes");
        }
        if (r.cantidad() == null || r.cantidad() <= 0) {
            throw NegocioException.invalido("La cantidad debe ser mayor que cero");
        }
        if (r.motivo() == null || r.motivo().isBlank()) {
            throw NegocioException.invalido("El motivo es obligatorio");
        }

        Producto producto = productos.findById(r.productoId())
                .orElseThrow(() -> NegocioException.noEncontrado("Producto", r.productoId()));
        boolean desdeDiscrepancia = discrepancia != null;
        if (!producto.isActivo() && !desdeDiscrepancia
                && tipo != TipoMovimiento.SALIDA && tipo != TipoMovimiento.AJUSTE_NEGATIVO) {
            throw NegocioException.invalido("El producto esta desactivado");
        }
        Usuario usuario = usuarios.findByUsername(username)
                .orElseThrow(() -> NegocioException.noEncontrado("Usuario", username));

        Ubicacion origen = null;
        Ubicacion destino = null;
        if (tipo.restaEnOrigen()) {
            if (r.ubicacionOrigenId() == null) throw NegocioException.invalido("Falta la ubicacion de origen");
            origen = ubicacion(r.ubicacionOrigenId(), !desdeDiscrepancia);
        } else if (r.ubicacionOrigenId() != null) {
            throw NegocioException.invalido("Un movimiento " + tipo + " no lleva ubicacion de origen");
        }
        if (tipo.sumaEnDestino()) {
            if (r.ubicacionDestinoId() == null) throw NegocioException.invalido("Falta la ubicacion de destino");
            destino = ubicacion(r.ubicacionDestinoId(), !desdeDiscrepancia);
        } else if (r.ubicacionDestinoId() != null) {
            throw NegocioException.invalido("Un movimiento " + tipo + " no lleva ubicacion de destino");
        }
        if (origen != null && destino != null && origen.getId().equals(destino.getId())) {
            throw NegocioException.invalido("Origen y destino no pueden ser iguales");
        }

        if (origen != null && destino != null && destino.getId() < origen.getId()) {
            Inventario invDestino = obtenerOCrearBloqueado(producto, destino);
            Inventario invOrigen = obtenerOCrearBloqueado(producto, origen);
            descontar(invOrigen, r.cantidad());
            invDestino.setCantidad(invDestino.getCantidad() + r.cantidad());
        } else {
            if (origen != null) descontar(obtenerOCrearBloqueado(producto, origen), r.cantidad());
            if (destino != null) {
                Inventario invDestino = obtenerOCrearBloqueado(producto, destino);
                invDestino.setCantidad(invDestino.getCantidad() + r.cantidad());
            }
        }

        Movimiento m = new Movimiento();
        m.setTipo(tipo);
        m.setProducto(producto);
        m.setUbicacionOrigen(origen);
        m.setUbicacionDestino(destino);
        m.setCantidad(r.cantidad());
        m.setMotivo(r.motivo().trim());
        m.setUsuario(usuario);
        m.setDiscrepancia(discrepancia);
        m = movimientos.save(m);

        auditoria.registrar("MOVIMIENTO_" + tipo, "movimiento", m.getId(),
                producto.getCodigo() + " x" + r.cantidad()
                        + (origen == null ? "" : " desde " + origen.getNombre())
                        + (destino == null ? "" : " hacia " + destino.getNombre())
                        + " | " + m.getMotivo());
        inventario.flush();
        reglasStock.evaluarStockMinimo(producto);
        return m;
    }

    private void descontar(Inventario inv, int cantidad) {
        if (inv.getCantidad() < cantidad) {
            throw NegocioException.conflicto("Stock insuficiente en " + inv.getUbicacion().getNombre()
                    + ": disponible " + inv.getCantidad() + ", solicitado " + cantidad);
        }
        inv.setCantidad(inv.getCantidad() - cantidad);
    }

    private Inventario obtenerOCrearBloqueado(Producto p, Ubicacion u) {
        return inventario.bloquear(p.getId(), u.getId()).orElseGet(() -> {
            inventario.crearSiNoExiste(p.getId(), u.getId());
            return inventario.bloquear(p.getId(), u.getId()).orElseThrow();
        });
    }

    private Ubicacion ubicacion(Long id, boolean exigirActiva) {
        Ubicacion u = ubicaciones.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Ubicacion", id));
        if (exigirActiva && !u.isActivo()) throw NegocioException.invalido("La ubicacion " + u.getNombre() + " esta desactivada");
        return u;
    }

    @Transactional(readOnly = true)
    public List<InventarioResponse> existencias(Long productoId, Long ubicacionId) {
        return inventario.filtrar(productoId, ubicacionId).stream().map(InventarioResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public PaginaResponse<MovimientoResponse> historial(Long productoId, String usuario, OffsetDateTime desde,
                                                        OffsetDateTime hasta, int pagina, int tamano) {
        Page<Movimiento> page = movimientos.filtrar(productoId, usuario == null ? "" : usuario.trim(), desde, hasta,
                PageRequest.of(Math.max(pagina, 0), Math.min(Math.max(tamano, 1), 500)));
        return new PaginaResponse<>(page.getContent().stream().map(MovimientoResponse::de).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public int cantidadRegistrada(Long productoId, Long ubicacionId) {
        return inventario.findByProductoIdAndUbicacionId(productoId, ubicacionId).map(Inventario::getCantidad).orElse(0);
    }
}
