package pe.edu.unaj.smartstock.ia;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/ia")
public class IaController {

    public record MensajeRequest(@NotBlank @Size(max = 1000) String texto) { }

    public record AprenderRequest(@NotBlank @Size(max = 500) String texto, @NotBlank @Size(max = 40) String intencion) { }

    public record RecordarRequest(@NotBlank @Size(max = 500) String texto, boolean preferencia) { }

    private final AsistenteIa asistente;
    private final MemoriaIa memoria;
    private final PronosticoService pronostico;

    public IaController(AsistenteIa asistente, MemoriaIa memoria, PronosticoService pronostico) {
        this.asistente = asistente;
        this.memoria = memoria;
        this.pronostico = pronostico;
    }

    @PostMapping("/mensaje")
    public AsistenteIa.Respuesta mensaje(@Valid @RequestBody MensajeRequest r, Authentication auth) {
        return asistente.mensaje(r.texto(), auth);
    }

    @PostMapping("/confirmar/{id}")
    public AsistenteIa.Respuesta confirmar(@PathVariable String id, Authentication auth) {
        return asistente.confirmar(id, auth);
    }

    @PostMapping("/cancelar/{id}")
    public AsistenteIa.Respuesta cancelar(@PathVariable String id, Authentication auth) {
        return asistente.cancelar(id, auth);
    }

    @PostMapping("/aprender")
    public AsistenteIa.Respuesta aprender(@Valid @RequestBody AprenderRequest r, Authentication auth) {
        return asistente.aprender(r.texto(), r.intencion(), auth);
    }

    @GetMapping("/historial")
    public List<AsistenteIa.Mensaje> historial(Authentication auth) { return asistente.historial(auth); }

    @GetMapping("/pendientes")
    public List<AsistenteIa.Accion> pendientes(Authentication auth) { return asistente.pendientes(auth); }

    @PostMapping("/reiniciar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reiniciar(Authentication auth) { asistente.reiniciar(auth); }

    @GetMapping("/estado")
    public AsistenteIa.Estado estado() { return asistente.estado(); }

    @GetMapping("/memoria")
    public List<MemoriaIa.Recuerdo> memoria() { return memoria.todos(); }

    @PostMapping("/memoria")
    @ResponseStatus(HttpStatus.CREATED)
    public MemoriaIa.Recuerdo recordar(@Valid @RequestBody RecordarRequest r, Authentication auth) {
        return memoria.recordar(r.preferencia() ? MemoriaIa.Tipo.PREFERENCIA : MemoriaIa.Tipo.HECHO, r.texto(), auth.getName());
    }

    @DeleteMapping("/memoria/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void olvidar(@PathVariable Long id, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
        memoria.olvidar(id, auth.getName(), admin);
    }

    @GetMapping("/pronostico")
    public List<PronosticoService.Pronostico> pronostico() { return pronostico.calcular(); }
}
