package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/** Existencias por producto y ubicacion. Solo MovimientoService la modifica. */
@Entity
@Table(name = "inventario")
public class Inventario {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "producto_id", nullable = false)
    private Producto producto;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ubicacion_id", nullable = false)
    private Ubicacion ubicacion;

    @Column(nullable = false)
    private Integer cantidad = 0;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Inventario() { }

    public Inventario(Producto producto, Ubicacion ubicacion) {
        this.producto = producto;
        this.ubicacion = ubicacion;
        this.cantidad = 0;
    }

    public Long getId() { return id; }
    public Producto getProducto() { return producto; }
    public Ubicacion getUbicacion() { return ubicacion; }
    public Integer getCantidad() { return cantidad; }
    public void setCantidad(Integer cantidad) { this.cantidad = cantidad; this.updatedAt = OffsetDateTime.now(); }
    public Integer getVersion() { return version; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
