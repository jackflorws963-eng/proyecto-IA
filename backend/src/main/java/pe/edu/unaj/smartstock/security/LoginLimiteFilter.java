package pe.edu.unaj.smartstock.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class LoginLimiteFilter extends OncePerRequestFilter {

    public static final String URL_LOGIN = "/api/auth/login";

    private final IntentosFallidos intentos;

    public LoginLimiteFilter(IntentosFallidos intentos) {
        this.intentos = intentos;
    }

    public static String clave(HttpServletRequest req, String username) {
        return "login:" + username + "@" + req.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && URL_LOGIN.equals(req.getServletPath()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (intentos.bloqueado(clave(req, req.getParameter("username")))) {
            RespuestaJson.escribir(res, 429, "Demasiados intentos fallidos. Espere unos minutos e intente de nuevo");
            return;
        }
        chain.doFilter(req, res);
    }
}
