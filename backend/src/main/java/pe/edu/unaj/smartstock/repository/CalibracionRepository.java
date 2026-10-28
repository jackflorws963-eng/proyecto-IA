package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Calibracion;

import java.util.List;
import java.util.Optional;

public interface CalibracionRepository extends JpaRepository<Calibracion, Long> {
    Optional<Calibracion> findBySensorIdAndVigenteTrue(Long sensorId);
    List<Calibracion> findBySensorIdOrderByCreatedAtDesc(Long sensorId);
}
