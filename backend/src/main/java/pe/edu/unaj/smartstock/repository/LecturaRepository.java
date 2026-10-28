package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Lectura;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface LecturaRepository extends JpaRepository<Lectura, Long> {

    Optional<Lectura> findBySensorIdAndSeq(Long sensorId, Long seq);

    /** Usa el indice unico (sensor_id, seq): consulta inmediata aunque haya millones de lecturas. */
    @Query("SELECT MAX(l.seq) FROM Lectura l WHERE l.sensor.id = :sensorId")
    Long maxSeq(@Param("sensorId") Long sensorId);

    Optional<Lectura> findFirstBySensorIdOrderByMedidoEnDesc(Long sensorId);

    @Query("""
           SELECT l FROM Lectura l
           WHERE l.sensor.id = :sensorId AND l.medidoEn >= :desde AND l.medidoEn < :hasta
           ORDER BY l.medidoEn ASC
           """)
    List<Lectura> serie(@Param("sensorId") Long sensorId,
                        @Param("desde") OffsetDateTime desde,
                        @Param("hasta") OffsetDateTime hasta);

    @Query("SELECT COUNT(l) FROM Lectura l WHERE l.recibidoEn >= :desde")
    long contarDesde(@Param("desde") OffsetDateTime desde);
}
