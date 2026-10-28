package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Enums;
import pe.edu.unaj.smartstock.entity.Sensor;

import java.util.List;
import java.util.Optional;

public interface SensorRepository extends JpaRepository<Sensor, Long> {
    Optional<Sensor> findByDispositivoIdAndTipo(Long dispositivoId, Enums.TipoSensor tipo);
    List<Sensor> findByDispositivoIdOrderByTipo(Long dispositivoId);
    List<Sensor> findAllByOrderByIdAsc();
}
