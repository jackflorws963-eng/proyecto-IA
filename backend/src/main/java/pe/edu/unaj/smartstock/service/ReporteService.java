package pe.edu.unaj.smartstock.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ReporteService {

    private final MovimientoService movimientos;
    private final AlertaService alertas;
    private final IotService iot;
    private final EstimacionService estimacion;
    private final VentaService ventas;

    public ReporteService(MovimientoService movimientos, AlertaService alertas, IotService iot, EstimacionService estimacion,
                          VentaService ventas) {
        this.movimientos = movimientos;
        this.alertas = alertas;
        this.iot = iot;
        this.estimacion = estimacion;
        this.ventas = ventas;
    }

    @Transactional(readOnly = true)
    public String existencias(Long productoId, Long ubicacionId) {
        StringBuilder sb = cabecera("producto_codigo;producto;ubicacion;cantidad;stock_minimo;bajo_minimo;actualizado");
        for (InventarioResponse i : movimientos.existencias(productoId, ubicacionId)) {
            fila(sb, i.productoCodigo(), i.productoNombre(), i.ubicacion(), i.cantidad(), i.stockMinimo(),
                    i.cantidad() < i.stockMinimo() ? "SI" : "NO", i.actualizado());
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String movimientos(Long productoId, String usuario, OffsetDateTime desde, OffsetDateTime hasta) {
        StringBuilder sb = cabecera("id;fecha;tipo;producto;origen;destino;cantidad;motivo;usuario;discrepancia");
        PaginaResponse<MovimientoResponse> pagina;
        int n = 0;
        do {
            pagina = movimientos.historial(productoId, usuario, desde, hasta, n++, 500);
            for (MovimientoResponse m : pagina.contenido()) {
                fila(sb, m.id(), m.fecha(), m.tipo(), m.producto(), m.ubicacionOrigen(), m.ubicacionDestino(),
                        m.cantidad(), m.motivo(), m.usuario(), m.discrepanciaId());
            }
        } while (n < pagina.totalPaginas());
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String lecturas(Long sensorId, OffsetDateTime desde, OffsetDateTime hasta) {
        StringBuilder sb = cabecera("id;sensor_id;tipo;dispositivo;valor;seq;medido_en;recibido_en;estado;observacion");
        for (LecturaResponse l : iot.lecturas(sensorId, desde, hasta)) {
            fila(sb, l.id(), l.sensorId(), l.tipo(), l.dispositivo(), l.valor(), l.seq(), l.medidoEn(),
                    l.recibidoEn(), l.estado(), l.observacion());
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String alertas(EstadoAlerta estado, OffsetDateTime desde, OffsetDateTime hasta) {
        StringBuilder sb = cabecera("id;tipo;prioridad;estado;mensaje;ocurrencias;primera_vez;ultima_vez;reconocida_por;cerrada_en");
        for (AlertaResponse a : alertas.listar(estado, desde, hasta)) {
            fila(sb, a.id(), a.tipo(), a.prioridad(), a.estado(), a.mensaje(), a.ocurrencias(), a.primeraVez(),
                    a.ultimaVez(), a.reconocidaPor(), a.cerradaEn());
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String discrepancias() {
        StringBuilder sb = cabecera("id;estado;producto;ubicacion;peso_neto_g;estimado;registrado;diferencia_pct;revisado_por;comentario;fecha");
        for (DiscrepanciaResponse d : estimacion.listarDiscrepancias(false)) {
            EstimacionResponse e = d.estimacion();
            fila(sb, d.id(), d.estado(), e.producto(), e.ubicacion(), e.pesoNetoG(), e.cantidadEstimada(),
                    e.cantidadRegistrada(), e.diferenciaPct(), d.revisadoPor(), d.comentario(), d.fecha());
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public String ventas(OffsetDateTime desde, OffsetDateTime hasta) {
        StringBuilder sb = cabecera("numero;fecha;comprobante;estado;cliente_documento;cliente;metodo_pago;vendedor;"
                + "ubicacion;producto_codigo;producto;cantidad;precio_unitario;importe;total_venta;igv_venta");
        PaginaResponse<VentaResponse> pagina;
        int n = 0;
        do {
            pagina = ventas.listar(null, desde, hasta, n++, 500);
            for (VentaResponse v : pagina.contenido()) {
                for (VentaDetalleResponse d : v.detalles()) {
                    fila(sb, v.numero(), v.fecha(), v.tipoComprobante(), v.estado(), v.clienteDocumento(), v.clienteNombre(),
                            v.metodoPago(), v.vendedor(), v.ubicacion(), d.codigo(), d.producto(), d.cantidad(),
                            d.precioUnitario(), d.importe(), v.total(), v.igv());
                }
            }
        } while (n < pagina.totalPaginas());
        return sb.toString();
    }

    private static StringBuilder cabecera(String columnas) {
        return new StringBuilder("﻿").append(columnas).append("\r\n");
    }

    private static void fila(StringBuilder sb, Object... valores) {
        for (int i = 0; i < valores.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(escapar(valores[i]));
        }
        sb.append("\r\n");
    }

    static String escapar(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        if (s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    public static List<String> tipos() {
        return List.of("existencias", "movimientos", "lecturas", "alertas", "discrepancias", "ventas");
    }
}
