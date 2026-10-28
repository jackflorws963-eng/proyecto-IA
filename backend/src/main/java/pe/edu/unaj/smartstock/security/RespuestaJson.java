package pe.edu.unaj.smartstock.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Respuesta JSON minima para los errores que se producen en los filtros de seguridad. */
public final class RespuestaJson {
    private RespuestaJson() { }

    /** El mensaje debe ser un texto fijo del sistema (no se escapa). */
    public static void escribir(HttpServletResponse res, int status, String mensaje) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":" + status + ",\"mensaje\":\"" + mensaje + "\"}");
    }
}
