package pe.edu.unaj.smartstock.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Movimiento;

import java.time.OffsetDateTime;

public interface MovimientoRepository extends JpaRepository<Movimiento, Long> {

    @Query("""
           SELECT m FROM Movimiento m
           WHERE (:productoId IS NULL OR m.producto.id = :productoId)
             AND (:usuario = '' OR m.usuario.username = :usuario)
             AND m.createdAt >= :desde AND m.createdAt < :hasta
           ORDER BY m.createdAt DESC
           """)
    Page<Movimiento> filtrar(@Param("productoId") Long productoId,
                             @Param("usuario") String usuario,
                             @Param("desde") OffsetDateTime desde,
                             @Param("hasta") OffsetDateTime hasta,
                             Pageable pageable);
}
