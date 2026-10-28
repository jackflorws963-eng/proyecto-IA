package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoVenta;
import pe.edu.unaj.smartstock.service.VentaService;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/ventas")
public class
VentaController {

    private final VentaService service;

    public VentaController(VentaService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VentaResponse registrar(@Valid @RequestBody VentaRequest r, Authentication auth) {
        return service.registrar(r, auth.getName());
    }

    @GetMapping
    public PaginaResponse<VentaResponse> listar(
            @RequestParam(required = false) EstadoVenta estado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return service.listar(estado, InventarioController.inicio(desde, 7), InventarioController.fin(hasta), pagina, tamano);
    }

    @GetMapping("/resumen")
    public ResumenVentas resumen() { return service.resumen(); }

    @GetMapping("/{id}")
    public VentaResponse obtener(@PathVariable Long id) { return service.obtener(id); }

    @PostMapping("/{id}/anular")
    public VentaResponse anular(@PathVariable Long id, @Valid @RequestBody AnulacionRequest r, Authentication auth) {
        return service.anular(id, r.motivo(), auth.getName());
    }
}
