package pe.edu.unaj.smartstock.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.unaj.smartstock.dto.Dtos.UsuarioActual;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.UsuarioRepository;

@RestController
@RequestMapping("/api/auth")

public class AuthController {

    private final UsuarioRepository usuarios;

    public AuthController(UsuarioRepository usuarios) {
        this.usuarios = usuarios;
    }

    @GetMapping("/me")
    public UsuarioActual me(Authentication auth) {
        return usuarios.findByUsername(auth.getName())
                .map(u -> new UsuarioActual(u.getUsername(), u.getNombreCompleto(), u.getRol().getNombre()))
                .orElseThrow(() -> NegocioException.noEncontrado("Usuario", auth.getName()));
    }

    static boolean esAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
