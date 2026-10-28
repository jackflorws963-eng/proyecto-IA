package pe.edu.unaj.smartstock.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Inventario;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface InventarioRepository extends JpaRepository<Inventario, Long> {

    Optional<Inventario> findByProductoIdAndUbicacionId(Long productoId, Long ubicacionId);

    /** Bloquea la fila (SELECT ... FOR UPDATE) hasta que termine la transaccion. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventario i WHERE i.producto.id = :productoId AND i.ubicacion.id = :ubicacionId")
    Optional<Inventario> bloquear(@Param("productoId") Long productoId, @Param("ubicacionId") Long ubicacionId);

    /** Crea la fila con cantidad 0 si no existe; no falla si otra transaccion la crea a la vez. */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO inventario (producto_id, ubicacion_id) VALUES (:productoId, :ubicacionId) "
            + "ON CONFLICT (producto_id, ubicacion_id) DO NOTHING", nativeQuery = true)
    void crearSiNoExiste(@Param("productoId") Long productoId, @Param("ubicacionId") Long ubicacionId);

    /** Stock total por producto en una sola consulta: filas [productoId, suma]. */
    @Query("SELECT i.producto.id, SUM(i.cantidad) FROM Inventario i WHERE i.producto.id IN :ids GROUP BY i.producto.id")
    List<Object[]> stockTotales(@Param("ids") Collection<Long> ids);

    @Query("""
           SELECT i FROM Inventario i
           WHERE (:productoId IS NULL OR i.producto.id = :productoId)
             AND (:ubicacionId IS NULL OR i.ubicacion.id = :ubicacionId)
           ORDER BY i.producto.codigo, i.ubicacion.nombre
           """)
    List<Inventario> filtrar(@Param("productoId") Long productoId, @Param("ubicacionId") Long ubicacionId);

    @Query("SELECT COALESCE(SUM(i.cantidad), 0) FROM Inventario i WHERE i.producto.id = :productoId")
    long stockTotal(@Param("productoId") Long productoId);

    /** Productos activos cuyo stock total esta por debajo del minimo. */
    @Query("""
           SELECT COUNT(p) FROM Producto p
           WHERE p.activo = true AND p.stockMinimo >
                 (SELECT COALESCE(SUM(i.cantidad), 0) FROM Inventario i WHERE i.producto = p)
           """)
    long contarBajoMinimo();
}
