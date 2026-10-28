package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.DiagnosticoResponse;
import pe.edu.unaj.smartstock.dto.Dtos.EjecutarAccionesRequest;
import pe.edu.unaj.smartstock.dto.Dtos.MensajeAsistenteRequest;
import pe.edu.unaj.smartstock.dto.Dtos.RespuestaAsistente;
import pe.edu.unaj.smartstock.service.AsistenteService;

@RestController
@RequestMapping("/api/asistente")
public class AsistenteController {

    private final AsistenteService asistente;

    public AsistenteController(AsistenteService asistente) {
        this.asistente = asistente;
    }

    @GetMapping("/diagnostico")
    public DiagnosticoResponse diagnostico() { return asistente.diagnosticar(); }

    @PostMapping("/mensaje")
    public RespuestaAsistente mensaje(@Valid @RequestBody MensajeAsistenteRequest r, Authentication auth) {
        return asistente.responder(r.texto(), auth);
    }

    @PostMapping("/ejecutar")
    public RespuestaAsistente ejecutar(@Valid @RequestBody EjecutarAccionesRequest r, Authentication auth) {
        return asistente.ejecutar(r.acciones(), auth);
    }
}
