package pe.edu.unaj.smartstock.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class RespuestaJson {
    private RespuestaJson() { }

    public static void escribir(HttpServletResponse res, int status, String mensaje) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"status\":" + status + ",\"mensaje\":\"" + mensaje + "\"}");
    }
}
