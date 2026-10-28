package pe.edu.unaj.smartstock.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * CSRF para un frontend JavaScript (patron recomendado por Spring Security):
 * el token viaja en la cookie XSRF-TOKEN (legible por JS del mismo origen) y el
 * frontend lo devuelve en la cabecera X-XSRF-TOKEN. Otro sitio no puede leer la
 * cookie, asi que no puede falsificar la cabecera.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plano = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, Supplier<CsrfToken> csrfToken) {
        xor.handle(req, res, csrfToken);
        // Fuerza la carga del token para que la cookie XSRF-TOKEN se envie en cada respuesta.
        csrfToken.get();
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest req, CsrfToken csrfToken) {
        String cabecera = req.getHeader(csrfToken.getHeaderName());
        // Cabecera (frontend JS): valor tal cual de la cookie. Parametro de formulario: valor enmascarado (XOR).
        return (StringUtils.hasText(cabecera) ? plano : xor).resolveCsrfTokenValue(req, csrfToken);
    }
}
