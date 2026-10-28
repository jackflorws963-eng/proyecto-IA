package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.LoteLecturasRequest;
import pe.edu.unaj.smartstock.dto.Dtos.LoteLecturasResponse;
import pe.edu.unaj.smartstock.security.DispositivoAuthFilter.DispositivoPrincipal;
import pe.edu.unaj.smartstock.service.IotService;

/** Endpoint que consume el ESP32 (autenticado con X-Device-Code / X-Device-Key). */
@RestController
@RequestMapping("/api/iot")
public class IotController {

    private final IotService service;

    public IotController(IotService service) {
        this.service = service;
    }

    /**
     * POST /api/iot/lecturas
     * {"lecturas":[{"tipo":"TEMPERATURA","valor":21.4,"seq":15,"ts":1761050000}, ...]}
     * Respuesta 200 con el resultado por lectura (GUARDADA / DUPLICADA / RECHAZADA).
     */
    @PostMapping("/lecturas")
    public LoteLecturasResponse recibir(@Valid @RequestBody LoteLecturasRequest lote, Authentication auth) {
        DispositivoPrincipal d = (DispositivoPrincipal) auth.getPrincipal();
        return service.recibir(d.id(), lote);
    }

    /** GET /api/iot/ping: el ESP32 comprueba conectividad y credenciales. */
    @GetMapping("/ping")
    public String ping(Authentication auth) {
        return "ok " + auth.getName();
    }
}
