package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.DiagnosticoResponse;
import pe.edu.unaj.smartstock.dto.Dtos.EjecutarAccionesRequest;
import pe.edu.unaj.smartstock.dto.Dtos.MensajeAsistenteRequest;
import pe.edu.unaj.smartstock.dto.Dtos.RespuestaAsistente;
import pe.edu.unaj.smartstock.service.AsistenteService;

/**
 * Asistente (cualquier usuario autenticado). Las acciones sobre el ventilador
 * verifican el rol ADMIN dentro de AsistenteService.
 */
@RestController
@RequestMapping("/api/asistente")
public class AsistenteController {

    private final AsistenteService asistente;

    public AsistenteController(AsistenteService asistente) {
        this.asistente = asistente;
    }

    /** GET /api/asistente/diagnostico: problemas actuales con sugerencias y acciones posibles. */
    @GetMapping("/diagnostico")
    public DiagnosticoResponse diagnostico() { return asistente.diagnosticar(); }

    /** POST /api/asistente/mensaje {"texto":"¿qué errores hay?"} */
    @PostMapping("/mensaje")
    public RespuestaAsistente mensaje(@Valid @RequestBody MensajeAsistenteRequest r, Authentication auth) {
        return asistente.responder(r.texto(), auth);
    }

    /** POST /api/asistente/ejecutar {"acciones":["VENTILADOR:1:ENCENDIDO","RECONOCER:7"]} (acciones ya confirmadas). */
    @PostMapping("/ejecutar")
    public RespuestaAsistente ejecutar(@Valid @RequestBody EjecutarAccionesRequest r, Authentication auth) {
        return asistente.ejecutar(r.acciones(), auth);
    }
}
