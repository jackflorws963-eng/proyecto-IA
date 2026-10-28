package pe.edu.unaj.smartstock.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita los intentos fallidos de autenticacion (fuerza bruta).
 * Tras MAX_INTENTOS fallos dentro de VENTANA, la clave queda bloqueada durante BLOQUEO.
 * La clave combina usuario/dispositivo e IP: asi un atacante no puede bloquear
 * la cuenta de otra persona desde su propia maquina.
 * Se guarda en memoria: se reinicia al reiniciar el backend (suficiente para un solo servidor).
 */
@Component
public class IntentosFallidos {

    static final int MAX_INTENTOS = 5;
    static final Duration VENTANA = Duration.ofMinutes(15);
    static final Duration BLOQUEO = Duration.ofMinutes(5);
    private static final int MAX_CLAVES = 10_000;

    private record Registro(int fallos, Instant ultimo, Instant bloqueadoHasta) { }

    private final Map<String, Registro> registros = new ConcurrentHashMap<>();

    public boolean bloqueado(String clave) {
        Registro r = registros.get(clave);
        return r != null && r.bloqueadoHasta() != null && r.bloqueadoHasta().isAfter(Instant.now());
    }

    public void fallo(String clave) {
        Instant ahora = Instant.now();
        if (registros.size() > MAX_CLAVES) registros.values().removeIf(r -> vencido(r, ahora));
        registros.compute(clave, (k, r) -> {
            int fallos = (r == null || vencido(r, ahora)) ? 1 : r.fallos() + 1;
            return new Registro(fallos, ahora, fallos >= MAX_INTENTOS ? ahora.plus(BLOQUEO) : null);
        });
    }

    public void exito(String clave) {
        registros.remove(clave);
    }

    private static boolean vencido(Registro r, Instant ahora) {
        if (r.bloqueadoHasta() != null) return !r.bloqueadoHasta().isAfter(ahora);
        return r.ultimo().plus(VENTANA).isBefore(ahora);
    }
}
