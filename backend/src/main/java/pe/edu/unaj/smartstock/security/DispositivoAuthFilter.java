package pe.edu.unaj.smartstock.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.filter.OncePerRequestFilter;
import pe.edu.unaj.smartstock.entity.Dispositivo;
import pe.edu.unaj.smartstock.repository.DispositivoRepository;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Autenticacion de dispositivos IoT, independiente de los usuarios.
 * Cabeceras: X-Device-Code (codigo del ESP32) y X-Device-Key (clave secreta).
 * La clave se compara contra el hash BCrypt guardado en la tabla dispositivo.
 * Tras varios intentos fallidos desde una IP, esa IP queda bloqueada unos minutos.
 */
public class DispositivoAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DispositivoAuthFilter.class);

    private final DispositivoRepository dispositivos;
    private final PasswordEncoder encoder;
    private final IntentosFallidos intentos;

    public DispositivoAuthFilter(DispositivoRepository dispositivos, PasswordEncoder encoder, IntentosFallidos intentos) {
        this.dispositivos = dispositivos;
        this.encoder = encoder;
        this.intentos = intentos;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String codigo = req.getHeader("X-Device-Code");
        String clave = req.getHeader("X-Device-Key");

        if (codigo != null && clave != null) {
            String claveIntentos = "iot@" + req.getRemoteAddr();
            if (intentos.bloqueado(claveIntentos)) {
                log.warn("IP {} bloqueada temporalmente por intentos fallidos de dispositivo", req.getRemoteAddr());
            } else {
                Optional<Dispositivo> d = dispositivos.findByCodigo(codigo.trim());
                if (d.isPresent() && d.get().isActivo() && encoder.matches(clave, d.get().getApiKeyHash())) {
                    intentos.exito(claveIntentos);
                    var auth = new UsernamePasswordAuthenticationToken(
                            new DispositivoPrincipal(d.get().getId(), d.get().getCodigo()), null,
                            List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
                    // Contexto nuevo (no se modifica uno existente que pudiera estar compartido)
                    SecurityContext contexto = SecurityContextHolder.createEmptyContext();
                    contexto.setAuthentication(auth);
                    SecurityContextHolder.setContext(contexto);
                } else {
                    intentos.fallo(claveIntentos);
                    log.warn("Credenciales de dispositivo invalidas para codigo={} desde {}",
                            paraLog(codigo), req.getRemoteAddr());
                }
            }
        }
        chain.doFilter(req, res);
    }

    /** Evita inyectar saltos de linea u otros caracteres en el log (el codigo lo envia el cliente). */
    static String paraLog(String s) {
        String limpio = s.replaceAll("[^A-Za-z0-9_-]", "?");
        return limpio.length() > 40 ? limpio.substring(0, 40) + "..." : limpio;
    }

    /** Identidad del dispositivo autenticado. getName() se usa en la auditoria. */
    public record DispositivoPrincipal(Long id, String codigo) implements java.security.Principal {
        @Override
        public String getName() { return "device:" + codigo; }
    }
}
