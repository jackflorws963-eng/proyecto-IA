package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Comprobante de venta. No se borra ni se edita: solo puede pasar de EMITIDA a ANULADA (trigger en BD). */
@Entity
@Table(name = "venta")
public class Venta {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_comprobante", nullable = false, length = 15)
    private Enums.TipoComprobante tipoComprobante;

    @Column(nullable = false, length = 4)
    private String serie;

    @Column(nullable = false)
    private Integer correlativo;

    /** Ubicacion de la que sale la mercaderia. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ubicacion_id", nullable = false)
    private Ubicacion ubicacion;

    @Column(name = "cliente_documento", length = 11)
    private String clienteDocumento;

    @Column(name = "cliente_nombre", length = 120)
    private String clienteNombre;

    @Enumerated(EnumType.STRING)
    @Column(name = "metodo_pago", nullable = false, length = 15)
    private Enums.MetodoPago metodoPago;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal igv;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;

    @Column(name = "monto_recibido", precision = 12, scale = 2)
    private BigDecimal montoRecibido;

    @Column(precision = 12, scale = 2)
    private BigDecimal vuelto;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Enums.EstadoVenta estado = Enums.EstadoVenta.EMITIDA;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "anulada_por")
    private Usuario anuladaPor;

    @Column(name = "anulada_en")
    private OffsetDateTime anuladaEn;

    @Column(name = "motivo_anulacion", length = 255)
    private String motivoAnulacion;

    @OneToMany(mappedBy = "venta", cascade = CascadeType.PERSIST)
    @OrderBy("id")
    private List<VentaDetalle> detalles = new ArrayList<>();

    /** Numero del comprobante, p. ej. B001-00000042. */
    public String getNumero() { return serie + "-" + String.format("%08d", correlativo); }

    public void agregar(VentaDetalle d) {
        d.setVenta(this);
        detalles.add(d);
    }

    public Long getId() { return id; }
    public Enums.TipoComprobante getTipoComprobante() { return tipoComprobante; }
    public void setTipoComprobante(Enums.TipoComprobante tipoComprobante) { this.tipoComprobante = tipoComprobante; }
    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }
    public Integer getCorrelativo() { return correlativo; }
    public void setCorrelativo(Integer correlativo) { this.correlativo = correlativo; }
    public Ubicacion getUbicacion() { return ubicacion; }
    public void setUbicacion(Ubicacion ubicacion) { this.ubicacion = ubicacion; }
    public String getClienteDocumento() { return clienteDocumento; }
    public void setClienteDocumento(String clienteDocumento) { this.clienteDocumento = clienteDocumento; }
    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }
    public Enums.MetodoPago getMetodoPago() { return metodoPago; }
    public void setMetodoPago(Enums.MetodoPago metodoPago) { this.metodoPago = metodoPago; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getIgv() { return igv; }
    public void setIgv(BigDecimal igv) { this.igv = igv; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public BigDecimal getMontoRecibido() { return montoRecibido; }
    public void setMontoRecibido(BigDecimal montoRecibido) { this.montoRecibido = montoRecibido; }
    public BigDecimal getVuelto() { return vuelto; }
    public void setVuelto(BigDecimal vuelto) { this.vuelto = vuelto; }
    public Enums.EstadoVenta getEstado() { return estado; }
    public void setEstado(Enums.EstadoVenta estado) { this.estado = estado; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Usuario getAnuladaPor() { return anuladaPor; }
    public void setAnuladaPor(Usuario anuladaPor) { this.anuladaPor = anuladaPor; }
    public OffsetDateTime getAnuladaEn() { return anuladaEn; }
    public void setAnuladaEn(OffsetDateTime anuladaEn) { this.anuladaEn = anuladaEn; }
    public String getMotivoAnulacion() { return motivoAnulacion; }
    public void setMotivoAnulacion(String motivoAnulacion) { this.motivoAnulacion = motivoAnulacion; }
    public List<VentaDetalle> getDetalles() { return detalles; }
}
