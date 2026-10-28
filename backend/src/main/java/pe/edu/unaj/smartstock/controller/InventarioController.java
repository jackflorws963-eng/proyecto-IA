package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.service.MovimientoService;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@RestController
@RequestMapping("/api")
public class InventarioController {

    static final ZoneId ZONA = ZoneId.of("America/Lima");

    private final MovimientoService service;

    public InventarioController(MovimientoService service) {
        this.service = service;
    }

    @GetMapping("/inventario")
    public List<InventarioResponse> existencias(@RequestParam(required = false) Long productoId,
                                                @RequestParam(required = false) Long ubicacionId) {
        return service.existencias(productoId, ubicacionId);
    }

    @PostMapping("/movimientos")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoResponse registrar(@Valid @RequestBody MovimientoRequest r, Authentication auth) {
        return service.registrar(r, auth.getName(), AuthController.esAdmin(auth));
    }

    /** GET /api/movimientos?productoId=1&usuario=admin&desde=2026-10-01&hasta=2026-10-31 */
    @GetMapping("/movimientos")
    public PaginaResponse<MovimientoResponse> historial(
            @RequestParam(required = false) Long productoId,
            @RequestParam(defaultValue = "") String usuario,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return service.historial(productoId, usuario, inicio(desde, 30), fin(hasta), pagina, tamano);
    }

    /** Inicio del dia en hora de Peru; por defecto, hace N dias. */
    static OffsetDateTime inicio(LocalDate d, int diasAtras) {
        LocalDate f = d != null ? d : LocalDate.now(ZONA).minusDays(diasAtras);
        return f.atStartOfDay(ZONA).toOffsetDateTime();
    }

    /** Fin exclusivo: inicio del dia siguiente. */
    static OffsetDateTime fin(LocalDate d) {
        LocalDate f = d != null ? d : LocalDate.now(ZONA);
        return f.plusDays(1).atStartOfDay(ZONA).toOffsetDateTime();
    }
}
