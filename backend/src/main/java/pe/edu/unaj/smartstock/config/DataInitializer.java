package pe.edu.unaj.smartstock.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.entity.Dispositivo;
import pe.edu.unaj.smartstock.entity.Rol;
import pe.edu.unaj.smartstock.entity.Usuario;
import pe.edu.unaj.smartstock.repository.DispositivoRepository;
import pe.edu.unaj.smartstock.repository.RolRepository;
import pe.edu.unaj.smartstock.repository.UsuarioRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    static final String ADMIN_DEV = "Admin123!";
    static final String OPERADOR_DEV = "Operador123!";
    static final String DEVICE_KEY_DEV = "dev-device-key-cambiar";
    static final String DB_PASSWORD_DEV = "postgres";

    private final UsuarioRepository usuarios;
    private final RolRepository roles;
    private final DispositivoRepository dispositivos;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String operadorPassword;
    private final String deviceKey;

    public DataInitializer(UsuarioRepository usuarios, RolRepository roles, DispositivoRepository dispositivos,
                           PasswordEncoder encoder,
                           @Value("${smartstock.seguridad.admin-password}") String adminPassword,
                           @Value("${smartstock.seguridad.operador-password}") String operadorPassword,
                           @Value("${smartstock.seguridad.device-key}") String deviceKey,
                           @Value("${spring.datasource.password}") String dbPassword,
                           @Value("${smartstock.seguridad.permitir-claves-desarrollo:false}") boolean permitirClavesDev) {
        this.usuarios = usuarios;
        this.roles = roles;
        this.dispositivos = dispositivos;
        this.encoder = encoder;
        this.adminPassword = adminPassword;
        this.operadorPassword = operadorPassword;
        this.deviceKey = deviceKey;
        verificarCredenciales(dbPassword, permitirClavesDev);
    }

    private void verificarCredenciales(String dbPassword, boolean permitirClavesDev) {
        List<String> deDesarrollo = new ArrayList<>();
        if (quedaDeDesarrollo(ADMIN_DEV, adminPassword, usuarios.findByUsername("admin").map(Usuario::getPasswordHash))) {
            deDesarrollo.add("ADMIN_PASSWORD");
        }
        if (quedaDeDesarrollo(OPERADOR_DEV, operadorPassword,
                usuarios.findByUsername("operador").map(Usuario::getPasswordHash))) {
            deDesarrollo.add("OPERADOR_PASSWORD");
        }
        if (quedaDeDesarrollo(DEVICE_KEY_DEV, deviceKey,
                dispositivos.findByCodigo("ESP32-01").map(Dispositivo::getApiKeyHash))) {
            deDesarrollo.add("DEVICE_KEY");
        }
        if (DB_PASSWORD_DEV.equals(dbPassword)) deDesarrollo.add("DB_PASSWORD");
        if (deDesarrollo.isEmpty()) return;
        if (!permitirClavesDev) {
            throw new IllegalStateException("Credenciales de DESARROLLO en uso: " + deDesarrollo
                    + ". Defina esas variables de entorno con valores propios o, solo en su PC de desarrollo,"
                    + " defina PERMITIR_CLAVES_DEV=true.");
        }
        log.warn("Usando credenciales de DESARROLLO ({}) porque PERMITIR_CLAVES_DEV=true. No use esto fuera de su PC.",
                deDesarrollo);
    }

    private boolean quedaDeDesarrollo(String valorDev, String valorNuevo, Optional<String> hashGuardado) {
        if (!valorDev.equals(valorNuevo)) return false;
        return hashGuardado.map(h -> esPendienteODesarrollo(h, valorDev)).orElse(true);
    }

    private boolean esPendienteODesarrollo(String hash, String valorDev) {
        return "PENDIENTE".equals(hash) || encoder.matches(valorDev, hash);
    }

    @Override
    @Transactional
    public void run(String... args) {
        crearOActualizarUsuario("admin", "Administrador del sistema", "ADMIN", adminPassword, ADMIN_DEV);
        crearOActualizarUsuario("operador", "Operador de almacen", "OPERADOR", operadorPassword, OPERADOR_DEV);

        dispositivos.findByCodigo("ESP32-01").ifPresent(d -> {
            if ("PENDIENTE".equals(d.getApiKeyHash())) {
                d.setApiKeyHash(encoder.encode(deviceKey));
                log.info("Clave del dispositivo ESP32-01 configurada desde DEVICE_KEY");
            } else if (!DEVICE_KEY_DEV.equals(deviceKey) && encoder.matches(DEVICE_KEY_DEV, d.getApiKeyHash())) {
                d.setApiKeyHash(encoder.encode(deviceKey));
                log.warn("La clave de desarrollo del ESP32-01 se reemplazo por DEVICE_KEY: actualice DEVICE_KEY en secrets.h");
            }
        });
    }

    private void crearOActualizarUsuario(String username, String nombre, String rolNombre, String password, String valorDev) {
        Optional<Usuario> existente = usuarios.findByUsername(username);
        if (existente.isPresent()) {
            Usuario u = existente.get();
            if (!valorDev.equals(password) && encoder.matches(valorDev, u.getPasswordHash())) {
                u.setPasswordHash(encoder.encode(password));
                log.info("Contrasena de desarrollo del usuario '{}' reemplazada por la de la variable de entorno", username);
            }
            return;
        }
        Rol rol = roles.findByNombre(rolNombre)
                .orElseThrow(() -> new IllegalStateException("Falta el rol " + rolNombre + " (revise migracion V2)"));
        Usuario u = new Usuario();
        u.setUsername(username);
        u.setNombreCompleto(nombre);
        u.setPasswordHash(encoder.encode(password));
        u.setRol(rol);
        usuarios.save(u);
        log.info("Usuario '{}' creado con rol {}", username, rolNombre);
    }
}
