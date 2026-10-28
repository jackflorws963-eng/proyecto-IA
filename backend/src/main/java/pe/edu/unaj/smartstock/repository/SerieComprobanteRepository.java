package pe.edu.unaj.smartstock.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pe.edu.unaj.smartstock.entity.Enums;
import pe.edu.unaj.smartstock.entity.SerieComprobante;

import java.util.Optional;

public interface SerieComprobanteRepository extends JpaRepository<SerieComprobante, Enums.TipoComprobante> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SerieComprobante s WHERE s.tipo = :tipo")
    Optional<SerieComprobante> bloquear(@Param("tipo") Enums.TipoComprobante tipo);
}
