package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.OffsetDateTime;

@Entity
@Immutable
@Table(name = "movimiento")
public class Movimiento {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.TipoMovimiento tipo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "producto_id", nullable = false)
    private Producto producto;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ubicacion_origen_id")
    private Ubicacion ubicacionOrigen;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "ubicacion_destino_id")
    private Ubicacion ubicacionDestino;

    @Column(nullable = false)
    private Integer cantidad;

    @Column(nullable = false, length = 255)
    private String motivo;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "usuario_id", nullable = false)
    private Usuario usuario;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "discrepancia_id")
    private Discrepancia discrepancia;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public Enums.TipoMovimiento getTipo() { return tipo; }
    public void setTipo(Enums.TipoMovimiento tipo) { this.tipo = tipo; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public Ubicacion getUbicacionOrigen() { return ubicacionOrigen; }
    public void setUbicacionOrigen(Ubicacion ubicacionOrigen) { this.ubicacionOrigen = ubicacionOrigen; }
    public Ubicacion getUbicacionDestino() { return ubicacionDestino; }
    public void setUbicacionDestino(Ubicacion ubicacionDestino) { this.ubicacionDestino = ubicacionDestino; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public Discrepancia getDiscrepancia() { return discrepancia; }
    public void setDiscrepancia(Discrepancia discrepancia) { this.discrepancia = discrepancia; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
