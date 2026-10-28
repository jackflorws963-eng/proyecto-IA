package pe.edu.unaj.smartstock.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Estimacion;

import java.util.List;
import java.util.Optional;

public interface EstimacionRepository extends JpaRepository<Estimacion, Long> {
    Optional<Estimacion> findFirstBySensorIdOrderByIdDesc(Long sensorId);
    List<Estimacion> findBySensorIdOrderByIdDesc(Long sensorId, Pageable pageable);
    List<Estimacion> findTop50BySensorIdOrderByCreatedAtDesc(Long sensorId);
}
