package pe.edu.unaj.smartstock.exception;

import org.springframework.http.HttpStatus;

/** Error de regla de negocio con su codigo HTTP. */
public class NegocioException extends RuntimeException {
    private final HttpStatus status;

    public NegocioException(HttpStatus status, String mensaje) {
        super(mensaje);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }

    public static NegocioException noEncontrado(String entidad, Object id) {
        return new NegocioException(HttpStatus.NOT_FOUND, entidad + " no encontrado(a): " + id);
    }

    public static NegocioException conflicto(String mensaje) {
        return new NegocioException(HttpStatus.CONFLICT, mensaje);
    }

    public static NegocioException invalido(String mensaje) {
        return new NegocioException(HttpStatus.UNPROCESSABLE_ENTITY, mensaje);
    }
}
