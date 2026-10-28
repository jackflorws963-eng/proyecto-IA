package pe.edu.unaj.smartstock.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.AuditoriaResponse;
import pe.edu.unaj.smartstock.entity.Auditoria;
import pe.edu.unaj.smartstock.repository.AuditoriaRepository;

import java.util.List;

/**
 * Registra acciones importantes. Se ejecuta dentro de la misma transaccion
 * que la operacion: si la operacion falla, tampoco queda registro (y viceversa).
 */
@Service
public class AuditoriaService {

    private final AuditoriaRepository repo;

    public AuditoriaService(AuditoriaRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(String accion, String entidad, Long entidadId, String detalle) {
        repo.save(new Auditoria(usuarioActual(), accion, entidad, entidadId, recortar(detalle)));
    }

    @Transactional(readOnly = true)
    public List<AuditoriaResponse> ultimos() {
        return repo.findTop200ByOrderByCreatedAtDesc().stream().map(AuditoriaResponse::de).toList();
    }

    public static String usuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth == null || auth.getName() == null) ? "sistema" : auth.getName();
    }

    private static String recortar(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
