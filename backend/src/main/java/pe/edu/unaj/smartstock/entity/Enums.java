package pe.edu.unaj.smartstock.entity;

/** Enumeraciones del dominio. Se guardan como texto (VARCHAR + CHECK en SQL). */
public final class Enums {
    private Enums() { }

    public enum TipoSensor { TEMPERATURA, HUMEDAD, PESO }

    public enum EstadoLectura { VALIDA, FUERA_RANGO }

    public enum TipoMovimiento {
        ENTRADA, SALIDA, AJUSTE_POSITIVO, AJUSTE_NEGATIVO, TRANSFERENCIA;

        public boolean esAjuste() { return this == AJUSTE_POSITIVO || this == AJUSTE_NEGATIVO; }
        public boolean sumaEnDestino() { return this == ENTRADA || this == AJUSTE_POSITIVO || this == TRANSFERENCIA; }
        public boolean restaEnOrigen() { return this == SALIDA || this == AJUSTE_NEGATIVO || this == TRANSFERENCIA; }
    }

    public enum EstadoDiscrepancia { PENDIENTE, APROBADA, RECHAZADA }

    public enum TipoAlerta { STOCK_MINIMO, TEMPERATURA, HUMEDAD, DISCREPANCIA, DISPOSITIVO_OFFLINE }

    public enum PrioridadAlerta { BAJA, MEDIA, ALTA }

    public enum EstadoAlerta { ABIERTA, RECONOCIDA, CERRADA }

    public enum ModoVentilador { AUTO, ENCENDIDO, APAGADO }

    public enum TipoComprobante { BOLETA, FACTURA, NOTA_VENTA }

    public enum MetodoPago { EFECTIVO, TARJETA, YAPE, PLIN, TRANSFERENCIA }

    public enum EstadoVenta { EMITIDA, ANULADA }
}
