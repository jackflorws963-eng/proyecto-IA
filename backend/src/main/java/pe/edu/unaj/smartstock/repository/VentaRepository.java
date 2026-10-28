package pe.edu.unaj.smartstock.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Enums;
import pe.edu.unaj.smartstock.entity.Venta;

import java.time.OffsetDateTime;
import java.util.List;

public interface VentaRepository extends JpaRepository<Venta, Long> {

    @Query("""
           SELECT v FROM Venta v
           WHERE (:estado IS NULL OR v.estado = :estado)
             AND v.createdAt >= :desde AND v.createdAt < :hasta
           ORDER BY v.createdAt DESC
           """)
    Page<Venta> filtrar(@Param("estado") Enums.EstadoVenta estado,
                        @Param("desde") OffsetDateTime desde,
                        @Param("hasta") OffsetDateTime hasta,
                        Pageable pageable);

    @Query("""
           SELECT COUNT(v), COALESCE(SUM(v.total), 0) FROM Venta v
           WHERE v.estado = pe.edu.unaj.smartstock.entity.Enums.EstadoVenta.EMITIDA
             AND v.createdAt >= :desde AND v.createdAt < :hasta
           """)
    List<Object[]> totales(@Param("desde") OffsetDateTime desde, @Param("hasta") OffsetDateTime hasta);

    /** [codigo, nombre, unidades, importe] de los productos mas vendidos (ventas no anuladas). */
    @Query("""
           SELECT d.producto.codigo, d.producto.nombre, SUM(d.cantidad), SUM(d.importe)
           FROM VentaDetalle d
           WHERE d.venta.estado = pe.edu.unaj.smartstock.entity.Enums.EstadoVenta.EMITIDA
             AND d.venta.createdAt >= :desde AND d.venta.createdAt < :hasta
           GROUP BY d.producto.codigo, d.producto.nombre
           ORDER BY SUM(d.importe) DESC
           """)
    List<Object[]> masVendidos(@Param("desde") OffsetDateTime desde, @Param("hasta") OffsetDateTime hasta,
                               Pageable pageable);
}
