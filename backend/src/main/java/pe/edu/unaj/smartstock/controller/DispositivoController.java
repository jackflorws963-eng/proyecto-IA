package pe.edu.unaj.smartstock.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Enums.ModoVentilador;
import pe.edu.unaj.smartstock.service.DispositivoService;
import pe.edu.unaj.smartstock.service.EstimacionService;
import pe.edu.unaj.smartstock.service.VentiladorService;

import java.util.List;

@RestController
@RequestMapping("/api")
public class DispositivoController {

    private final DispositivoService dispositivos;
    private final EstimacionService estimacion;
    private final VentiladorService ventilador;

    public DispositivoController(DispositivoService dispositivos, EstimacionService estimacion,
                                 VentiladorService ventilador) {
        this.dispositivos = dispositivos;
        this.estimacion = estimacion;
        this.ventilador = ventilador;
    }

    @GetMapping("/dispositivos")
    public List<DispositivoResponse> listar() { return dispositivos.listar(); }

    @PostMapping("/dispositivos")
    @ResponseStatus(HttpStatus.CREATED)
    public DispositivoCredencialResponse crear(@Valid @RequestBody DispositivoRequest r) { return dispositivos.crear(r); }

    @PostMapping("/dispositivos/{id}/regenerar-clave")
    public DispositivoCredencialResponse regenerar(@PathVariable Long id) { return dispositivos.regenerarClave(id); }

    @PatchMapping("/dispositivos/{id}/estado")
    public DispositivoResponse estado(@PathVariable Long id, @RequestParam boolean activo) {
        return dispositivos.cambiarEstado(id, activo);
    }

    @PatchMapping("/dispositivos/{id}/ventilador")
    public DispositivoResponse ventilador(@PathVariable Long id, @RequestParam ModoVentilador modo) {
        return ventilador.cambiarModo(id, modo);
    }

    @GetMapping("/sensores")
    public List<SensorResponse> sensores() { return dispositivos.listarSensores(); }

    @PostMapping("/sensores")
    @ResponseStatus(HttpStatus.CREATED)
    public SensorResponse crearSensor(@Valid @RequestBody SensorRequest r) { return dispositivos.crearSensor(r); }

    @PatchMapping("/sensores/{id}")
    public SensorResponse editarSensor(@PathVariable Long id,
                                       @RequestParam(required = false) Long productoId,
                                       @RequestParam(required = false) Boolean activo) {
        return dispositivos.editarSensor(id, productoId, activo);
    }

    @PostMapping("/calibraciones")
    @ResponseStatus(HttpStatus.CREATED)
    public CalibracionResponse calibrar(@Valid @RequestBody CalibracionRequest r, Authentication auth) {
        return estimacion.calibrar(r, auth.getName());
    }

    @GetMapping("/calibraciones")
    public List<CalibracionResponse> historial(@RequestParam Long sensorId) {
        return estimacion.historialCalibracion(sensorId);
    }
}
