package pe.edu.unaj.smartstock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pe.edu.unaj.smartstock.entity.Auditoria;

import java.util.List;

public interface AuditoriaRepository extends JpaRepository<Auditoria, Long> {
    List<Auditoria> findTop200ByOrderByCreatedAtDesc();
}
