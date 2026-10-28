package pe.edu.unaj.smartstock.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.PaginaResponse;
import pe.edu.unaj.smartstock.dto.Dtos.ProductoRequest;
import pe.edu.unaj.smartstock.dto.Dtos.ProductoResponse;
import pe.edu.unaj.smartstock.entity.Categoria;
import pe.edu.unaj.smartstock.entity.Producto;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.CategoriaRepository;
import pe.edu.unaj.smartstock.repository.InventarioRepository;
import pe.edu.unaj.smartstock.repository.ProductoRepository;

import java.util.HashMap;
import java.util.Map;

@Service
public class ProductoService {

    private final ProductoRepository productos;
    private final CategoriaRepository categorias;
    private final InventarioRepository inventario;
    private final AuditoriaService auditoria;
    private final ReglasStockService reglasStock;

    public ProductoService(ProductoRepository productos, CategoriaRepository categorias,
                           InventarioRepository inventario, AuditoriaService auditoria,
                           ReglasStockService reglasStock) {
        this.productos = productos;
        this.categorias = categorias;
        this.inventario = inventario;
        this.auditoria = auditoria;
        this.reglasStock = reglasStock;
    }

    @Transactional(readOnly = true)
    public PaginaResponse<ProductoResponse> buscar(String q, Long categoriaId, boolean soloActivos, int pagina, int tamano) {
        int t = Math.min(Math.max(tamano, 1), 100);
        Page<Producto> page = productos.buscar(q == null ? "" : q.trim(), categoriaId, soloActivos,
                PageRequest.of(Math.max(pagina, 0), t, Sort.by("codigo")));
        Map<Long, Long> stock = new HashMap<>();
        if (page.hasContent()) {
            for (Object[] fila : inventario.stockTotales(page.getContent().stream().map(Producto::getId).toList())) {
                stock.put((Long) fila[0], ((Number) fila[1]).longValue());
            }
        }
        return new PaginaResponse<>(
                page.getContent().stream().map(p -> ProductoResponse.de(p, stock.getOrDefault(p.getId(), 0L))).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    @Transactional(readOnly = true)
    public ProductoResponse obtener(Long id) {
        Producto p = buscarEntidad(id);
        return ProductoResponse.de(p, inventario.stockTotal(id));
    }

    @Transactional
    public ProductoResponse crear(ProductoRequest r) {
        String codigo = r.codigo().trim().toUpperCase();
        if (productos.existsByCodigoIgnoreCase(codigo)) throw NegocioException.conflicto("Ya existe el codigo " + codigo);
        Producto p = new Producto();
        p.setCodigo(codigo);
        aplicar(p, r);
        p = productos.save(p);
        auditoria.registrar("CREAR", "producto", p.getId(), codigo + " - " + p.getNombre());
        reglasStock.evaluarStockMinimo(p);
        return ProductoResponse.de(p, 0);
    }

    @Transactional
    public ProductoResponse editar(Long id, ProductoRequest r) {
        Producto p = buscarEntidad(id);
        String codigo = r.codigo().trim().toUpperCase();
        if (productos.existsByCodigoIgnoreCaseAndIdNot(codigo, id)) throw NegocioException.conflicto("Ya existe el codigo " + codigo);
        String antes = resumen(p);
        p.setCodigo(codigo);
        aplicar(p, r);
        auditoria.registrar("EDITAR", "producto", id, antes + " -> " + resumen(p));
        reglasStock.evaluarStockMinimo(p);
        return ProductoResponse.de(p, inventario.stockTotal(id));
    }

    @Transactional
    public ProductoResponse cambiarEstado(Long id, boolean activo) {
        Producto p = buscarEntidad(id);
        p.setActivo(activo);
        auditoria.registrar(activo ? "ACTIVAR" : "DESACTIVAR", "producto", id, p.getCodigo());
        reglasStock.evaluarStockMinimo(p);
        return ProductoResponse.de(p, inventario.stockTotal(id));
    }

    public Producto buscarEntidad(Long id) {
        return productos.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Producto", id));
    }

    private void aplicar(Producto p, ProductoRequest r) {
        Categoria c = categorias.findById(r.categoriaId())
                .orElseThrow(() -> NegocioException.noEncontrado("Categoria", r.categoriaId()));
        if (!c.isActivo()) throw NegocioException.invalido("La categoria esta desactivada");
        p.setNombre(r.nombre().trim());
        p.setDescripcion(r.descripcion());
        p.setCategoria(c);
        p.setUnidad(r.unidad().trim());
        p.setPrecio(r.precio());
        p.setPesoUnitarioG(r.pesoUnitarioG());
        p.setStockMinimo(r.stockMinimo());
    }

    private static String resumen(Producto p) {
        return p.getCodigo() + "|" + p.getNombre() + "|precio=" + p.getPrecio() + "|min=" + p.getStockMinimo()
                + "|pesoU=" + p.getPesoUnitarioG();
    }
}
