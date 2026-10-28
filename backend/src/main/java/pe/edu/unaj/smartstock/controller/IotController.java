package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.LoteLecturasRequest;
import pe.edu.unaj.smartstock.dto.Dtos.LoteLecturasResponse;
import pe.edu.unaj.smartstock.security.DispositivoAuthFilter.DispositivoPrincipal;
import pe.edu.unaj.smartstock.service.IotService;

@RestController
@RequestMapping("/api/iot")
public class IotController {

    private final IotService service;

    public IotController(IotService service) {
        this.service = service;
    }

    @PostMapping("/lecturas")
    public LoteLecturasResponse recibir(@Valid @RequestBody LoteLecturasRequest lote, Authentication auth) {
        DispositivoPrincipal d = (DispositivoPrincipal) auth.getPrincipal();
        return service.recibir(d.id(), lote);
    }

    @GetMapping("/ping")
    public String ping(Authentication auth) {
        return "ok " + auth.getName();
    }
}
