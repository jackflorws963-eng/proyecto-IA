package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;
import pe.edu.unaj.smartstock.service.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api")
public class MonitoreoController {

    private final DashboardService dashboard;
    private final IotService iot;
    private final EstimacionService estimacion;
    private final AlertaService alertas;
    private final DispositivoService dispositivos;
    private final AuditoriaService auditoria;

    public MonitoreoController(DashboardService dashboard, IotService iot, EstimacionService estimacion,
                               AlertaService alertas, DispositivoService dispositivos, AuditoriaService auditoria) {
        this.dashboard = dashboard;
        this.iot = iot;
        this.estimacion = estimacion;
        this.alertas = alertas;
        this.dispositivos = dispositivos;
        this.auditoria = auditoria;
    }

    @GetMapping("/dashboard/resumen")
    public ResumenDashboard resumen() { return dashboard.resumen(); }

    @GetMapping("/dashboard/ultimas-lecturas")
    public List<UltimaLectura> ultimas() { return iot.ultimasLecturas(); }

    @GetMapping("/dashboard/dispositivos")
    public List<DispositivoResponse> estadoDispositivos() { return dispositivos.listar(); }

    @GetMapping("/dashboard/alertas")
    public List<AlertaResponse> alertasActivas() { return alertas.activasRecientes(); }

    @GetMapping("/lecturas/serie")
    public List<PuntoSerie> serie(@RequestParam Long sensorId, @RequestParam(defaultValue = "6") int horas) {
        int h = Math.min(Math.max(horas, 1), 24 * 7);
        OffsetDateTime ahora = OffsetDateTime.now();
        return iot.serie(sensorId, ahora.minusHours(h), ahora.plusMinutes(1));
    }

    @GetMapping("/estimaciones")
    public List<EstimacionResponse> estimaciones(@RequestParam Long sensorId) {
        return estimacion.ultimasEstimaciones(sensorId);
    }

    @GetMapping("/discrepancias")
    public List<DiscrepanciaResponse> discrepancias(@RequestParam(defaultValue = "true") boolean soloPendientes) {
        return estimacion.listarDiscrepancias(soloPendientes);
    }

    @PostMapping("/discrepancias/{id}/aprobar")
    public DiscrepanciaResponse aprobar(@PathVariable Long id, @Valid @RequestBody RevisionRequest r, Authentication auth) {
        return estimacion.aprobar(id, auth.getName(), r.comentario().trim());
    }

    @PostMapping("/discrepancias/{id}/rechazar")
    public DiscrepanciaResponse rechazar(@PathVariable Long id, @Valid @RequestBody RevisionRequest r, Authentication auth) {
        return estimacion.rechazar(id, auth.getName(), r.comentario().trim());
    }

    @GetMapping("/alertas")
    public List<AlertaResponse> listarAlertas(
            @RequestParam(required = false) EstadoAlerta estado,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return alertas.listar(estado, InventarioController.inicio(desde, 30), InventarioController.fin(hasta));
    }

    @PatchMapping("/alertas/{id}/reconocer")
    public AlertaResponse reconocer(@PathVariable Long id, Authentication auth) {
        return alertas.reconocer(id, auth.getName());
    }

    @GetMapping("/auditoria")
    public List<AuditoriaResponse> auditoria() { return auditoria.ultimos(); }
}
