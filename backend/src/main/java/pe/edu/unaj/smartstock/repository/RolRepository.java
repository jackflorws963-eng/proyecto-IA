package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Rol;

import java.util.Optional;

public interface RolRepository extends JpaRepository<Rol, Long> {
    Optional<Rol> findByNombre(String nombre);
}
