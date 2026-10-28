package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Dispositivo;

import java.util.List;
import java.util.Optional;

public interface DispositivoRepository extends JpaRepository<Dispositivo, Long> {
    Optional<Dispositivo> findByCodigo(String codigo);
    boolean existsByCodigoIgnoreCase(String codigo);
    List<Dispositivo> findByActivoTrue();
    List<Dispositivo> findAllByOrderByCodigoAsc();
}
