package pe.edu.unaj.smartstock.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Producto;

public interface ProductoRepository extends JpaRepository<Producto, Long> {

    boolean existsByCodigoIgnoreCase(String codigo);
    boolean existsByCodigoIgnoreCaseAndIdNot(String codigo, Long id);
    long countByActivoTrue();

    @Query("""
           SELECT p FROM Producto p
           WHERE (:q = '' OR lower(p.nombre) LIKE lower(concat('%', :q, '%'))
                          OR lower(p.codigo) LIKE lower(concat('%', :q, '%')))
             AND (:categoriaId IS NULL OR p.categoria.id = :categoriaId)
             AND (:soloActivos = false OR p.activo = true)
           """)
    Page<Producto> buscar(@Param("q") String q,
                          @Param("categoriaId") Long categoriaId,
                          @Param("soloActivos") boolean soloActivos,
                          Pageable pageable);
}
