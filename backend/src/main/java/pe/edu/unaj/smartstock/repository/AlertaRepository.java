package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Alerta;
import pe.edu.unaj.smartstock.entity.Enums;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AlertaRepository extends JpaRepository<Alerta, Long> {

    Optional<Alerta> findFirstByClaveDedupAndEstadoNot(String claveDedup, Enums.EstadoAlerta estado);

    long countByEstadoNot(Enums.EstadoAlerta estado);

    @Query("""
           SELECT a FROM Alerta a
           WHERE (:estado IS NULL OR a.estado = :estado)
             AND a.ultimaVez >= :desde AND a.ultimaVez < :hasta
           ORDER BY a.ultimaVez DESC
           """)
    List<Alerta> filtrar(@Param("estado") Enums.EstadoAlerta estado,
                         @Param("desde") OffsetDateTime desde,
                         @Param("hasta") OffsetDateTime hasta);

    List<Alerta> findTop10ByEstadoNotOrderByUltimaVezDesc(Enums.EstadoAlerta estado);

    List<Alerta> findByEstadoNotOrderByUltimaVezDesc(Enums.EstadoAlerta estado);
}
