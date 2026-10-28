package pe.edu.unaj.smartstock.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Categoria;
import pe.edu.unaj.smartstock.entity.Ubicacion;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.CategoriaRepository;
import pe.edu.unaj.smartstock.repository.UbicacionRepository;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CatalogoService {

    private final CategoriaRepository categorias;
    private final UbicacionRepository ubicaciones;
    private final AuditoriaService auditoria;

    public CatalogoService(CategoriaRepository categorias, UbicacionRepository ubicaciones, AuditoriaService auditoria) {
        this.categorias = categorias;
        this.ubicaciones = ubicaciones;
        this.auditoria = auditoria;
    }

    @Transactional(readOnly = true)
    public List<CategoriaResponse> listarCategorias() {
        return categorias.findAllByOrderByNombreAsc().stream().map(CategoriaResponse::de).toList();
    }

    @Transactional
    public CategoriaResponse crearCategoria(CategoriaRequest r) {
        String nombre = r.nombre().trim();
        if (categorias.existsByNombreIgnoreCase(nombre)) throw NegocioException.conflicto("Ya existe la categoria " + nombre);
        Categoria c = new Categoria();
        c.setNombre(nombre);
        c.setDescripcion(r.descripcion());
        c.setActivo(r.activo() == null || r.activo());
        c = categorias.save(c);
        auditoria.registrar("CREAR", "categoria", c.getId(), nombre);
        return CategoriaResponse.de(c);
    }

    @Transactional
    public CategoriaResponse editarCategoria(Long id, CategoriaRequest r) {
        Categoria c = categorias.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Categoria", id));
        String nombre = r.nombre().trim();
        if (categorias.existsByNombreIgnoreCaseAndIdNot(nombre, id)) throw NegocioException.conflicto("Ya existe la categoria " + nombre);
        String antes = c.getNombre() + "|" + c.isActivo();
        c.setNombre(nombre);
        c.setDescripcion(r.descripcion());
        if (r.activo() != null) c.setActivo(r.activo());
        auditoria.registrar("EDITAR", "categoria", id, antes + " -> " + c.getNombre() + "|" + c.isActivo());
        return CategoriaResponse.de(c);
    }

    @Transactional(readOnly = true)
    public List<UbicacionResponse> listarUbicaciones() {
        return ubicaciones.findAllByOrderByNombreAsc().stream().map(UbicacionResponse::de).toList();
    }

    @Transactional
    public UbicacionResponse crearUbicacion(UbicacionRequest r) {
        validarRangos(r);
        String nombre = r.nombre().trim();
        if (ubicaciones.existsByNombreIgnoreCase(nombre)) throw NegocioException.conflicto("Ya existe la ubicacion " + nombre);
        Ubicacion u = new Ubicacion();
        aplicar(u, r);
        u = ubicaciones.save(u);
        auditoria.registrar("CREAR", "ubicacion", u.getId(), nombre);
        return UbicacionResponse.de(u);
    }

    @Transactional
    public UbicacionResponse editarUbicacion(Long id, UbicacionRequest r) {
        validarRangos(r);
        Ubicacion u = ubicaciones.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Ubicacion", id));
        if (ubicaciones.existsByNombreIgnoreCaseAndIdNot(r.nombre().trim(), id)) {
            throw NegocioException.conflicto("Ya existe la ubicacion " + r.nombre());
        }
        String antes = u.getNombre() + " T[" + u.getTempMin() + "," + u.getTempMax() + "] H[" + u.getHumMin() + "," + u.getHumMax() + "]";
        aplicar(u, r);
        auditoria.registrar("EDITAR", "ubicacion", id, antes + " -> T[" + u.getTempMin() + "," + u.getTempMax()
                + "] H[" + u.getHumMin() + "," + u.getHumMax() + "]");
        return UbicacionResponse.de(u);
    }

    private void aplicar(Ubicacion u, UbicacionRequest r) {
        u.setNombre(r.nombre().trim());
        u.setDescripcion(r.descripcion());
        u.setTempMin(r.tempMin());
        u.setTempMax(r.tempMax());
        u.setHumMin(r.humMin());
        u.setHumMax(r.humMax());
        if (r.activo() != null) u.setActivo(r.activo());
    }

    private void validarRangos(UbicacionRequest r) {
        BigDecimal minimo = IotService.HISTERESIS.multiply(BigDecimal.valueOf(2));
        if (r.tempMax().subtract(r.tempMin()).compareTo(minimo) <= 0) {
            throw NegocioException.invalido("tempMax debe superar a tempMin en mas de " + minimo.toPlainString());
        }
        if (r.humMax().subtract(r.humMin()).compareTo(minimo) <= 0) {
            throw NegocioException.invalido("humMax debe superar a humMin en mas de " + minimo.toPlainString());
        }
    }
}
