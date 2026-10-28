package pe.edu.unaj.smartstock.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.service.ReporteService;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/reportes")
public class ReporteController {

    static final int MAX_DIAS_LECTURAS = 31;

    private final ReporteService service;

    public ReporteController(ReporteService service) {
        this.service = service;
    }

    @GetMapping("/{tipo}.csv")
    public ResponseEntity<byte[]> csv(
            @PathVariable String tipo,
            @RequestParam(required = false) Long productoId,
            @RequestParam(required = false) Long ubicacionId,
            @RequestParam(required = false) Long sensorId,
            @RequestParam(defaultValue = "") String usuario,
            @RequestParam(required = false) EstadoAlerta estado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {

        var ini = InventarioController.inicio(desde, 30);
        var fin = InventarioController.fin(hasta);
        String contenido = switch (tipo) {
            case "existencias" -> service.existencias(productoId, ubicacionId);
            case "movimientos" -> service.movimientos(productoId, usuario, ini, fin);
            case "lecturas" -> {
                if (sensorId == null) throw NegocioException.invalido("El reporte de lecturas requiere sensorId");
                if (Duration.between(ini, fin).toDays() > MAX_DIAS_LECTURAS) {
                    throw NegocioException.invalido("El reporte de lecturas admite como maximo " + MAX_DIAS_LECTURAS + " dias");
                }
                yield service.lecturas(sensorId, ini, fin);
            }
            case "alertas" -> service.alertas(estado, ini, fin);
            case "discrepancias" -> service.discrepancias();
            case "ventas" -> service.ventas(ini, fin);
            default -> throw NegocioException.noEncontrado("Reporte", tipo + " (validos: " + ReporteService.tipos() + ")");
        };
        String archivo = "smartstock_" + tipo + "_" + LocalDate.now(InventarioController.ZONA) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archivo + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(contenido.getBytes(StandardCharsets.UTF_8));
    }
}
