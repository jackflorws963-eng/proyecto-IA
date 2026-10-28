package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Ubicacion;

import java.util.List;

public interface UbicacionRepository extends JpaRepository<Ubicacion, Long> {
    boolean existsByNombreIgnoreCase(String nombre);
    boolean existsByNombreIgnoreCaseAndIdNot(String nombre, Long id);
    List<Ubicacion> findAllByOrderByNombreAsc();
}
