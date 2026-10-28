package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Discrepancia;
import pe.edu.unaj.smartstock.entity.Enums;

import java.util.List;

public interface DiscrepanciaRepository extends JpaRepository<Discrepancia, Long> {
    boolean existsBySensorIdAndEstado(Long sensorId, Enums.EstadoDiscrepancia estado);
    List<Discrepancia> findByEstadoOrderByCreatedAtDesc(Enums.EstadoDiscrepancia estado);
    List<Discrepancia> findTop100ByOrderByCreatedAtDesc();
    long countByEstado(Enums.EstadoDiscrepancia estado);
}
