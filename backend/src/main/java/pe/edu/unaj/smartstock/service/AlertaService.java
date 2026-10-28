package pe.edu.unaj.smartstock.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.AlertaResponse;
import pe.edu.unaj.smartstock.entity.Alerta;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.PrioridadAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoAlerta;
import pe.edu.unaj.smartstock.entity.Usuario;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.AlertaRepository;
import pe.edu.unaj.smartstock.repository.UsuarioRepository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Reglas de alertas:
 *  - CREAR: si la condicion se cumple y no existe alerta no cerrada con la misma clave.
 *  - ACTUALIZAR: si ya existe, se incrementan ocurrencias y ultima_vez (no se duplica).
 *  - CERRAR: cuando la condicion se normaliza (automatico) o se resuelve la causa.
 *  - RECONOCER: un usuario indica que ya la vio; sigue activa hasta normalizarse.
 * La clave tiene la forma TIPO:entidad:id (ej. TEMPERATURA:ubicacion:1).
 */
@Service
public class AlertaService {

    private static final Logger log = LoggerFactory.getLogger(AlertaService.class);

    private final AlertaRepository repo;
    private final UsuarioRepository usuarios;
    private final AuditoriaService auditoria;

    public AlertaService(AlertaRepository repo, UsuarioRepository usuarios, AuditoriaService auditoria) {
        this.repo = repo;
        this.usuarios = usuarios;
        this.auditoria = auditoria;
    }

    public static String clave(TipoAlerta tipo, String entidad, Long id) {
        return tipo.name() + ":" + entidad + ":" + id;
    }

    @Transactional
    public Alerta abrirOActualizar(TipoAlerta tipo, String clave, PrioridadAlerta prioridad, String mensaje) {
        OffsetDateTime ahora = OffsetDateTime.now();
        return repo.findFirstByClaveDedupAndEstadoNot(clave, EstadoAlerta.CERRADA)
                .map(a -> {
                    a.setOcurrencias(a.getOcurrencias() + 1);
                    a.setUltimaVez(ahora);
                    a.setMensaje(recortar(mensaje));
                    if (prioridad.ordinal() > a.getPrioridad().ordinal()) a.setPrioridad(prioridad);
                    return a;
                })
                .orElseGet(() -> {
                    Alerta a = new Alerta();
                    a.setTipo(tipo);
                    a.setClaveDedup(clave);
                    a.setPrioridad(prioridad);
                    a.setMensaje(recortar(mensaje));
                    Alerta guardada = repo.save(a);
                    log.info("Alerta abierta {} -> {}", clave, mensaje);
                    auditoria.registrar("ABRIR", "alerta", guardada.getId(), clave + " | " + mensaje);
                    return guardada;
                });
    }

    @Transactional
    public void cerrarSiExiste(String clave, String motivo) {
        repo.findFirstByClaveDedupAndEstadoNot(clave, EstadoAlerta.CERRADA).ifPresent(a -> {
            a.setEstado(EstadoAlerta.CERRADA);
            a.setCerradaEn(OffsetDateTime.now());
            auditoria.registrar("CERRAR", "alerta", a.getId(), clave + " | " + motivo);
            log.info("Alerta cerrada {} ({})", clave, motivo);
        });
    }

    @Transactional
    public AlertaResponse reconocer(Long id, String username) {
        Alerta a = repo.findById(id).orElseThrow(() -> NegocioException.noEncontrado("Alerta", id));
        if (a.getEstado() != EstadoAlerta.ABIERTA) {
            throw NegocioException.conflicto("Solo se pueden reconocer alertas ABIERTAS");
        }
        Usuario u = usuarios.findByUsername(username)
                .orElseThrow(() -> NegocioException.noEncontrado("Usuario", username));
        a.setEstado(EstadoAlerta.RECONOCIDA);
        a.setReconocidaPor(u);
        a.setReconocidaEn(OffsetDateTime.now());
        auditoria.registrar("RECONOCER", "alerta", a.getId(), a.getClaveDedup());
        return AlertaResponse.de(a);
    }

    @Transactional(readOnly = true)
    public List<AlertaResponse> listar(EstadoAlerta estado, OffsetDateTime desde, OffsetDateTime hasta) {
        return repo.filtrar(estado, desde, hasta).stream().map(AlertaResponse::de).toList();
    }

    @Transactional(readOnly = true)
    public List<AlertaResponse> activasRecientes() {
        return repo.findTop10ByEstadoNotOrderByUltimaVezDesc(EstadoAlerta.CERRADA)
                .stream().map(AlertaResponse::de).toList();
    }

    private static String recortar(String s) {
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
