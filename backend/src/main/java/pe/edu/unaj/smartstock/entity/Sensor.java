package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sensor")
public class Sensor {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "dispositivo_id", nullable = false)
    private Dispositivo dispositivo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.TipoSensor tipo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "producto_id")
    private Producto producto;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Dispositivo getDispositivo() { return dispositivo; }
    public void setDispositivo(Dispositivo dispositivo) { this.dispositivo = dispositivo; }
    public Enums.TipoSensor getTipo() { return tipo; }
    public void setTipo(Enums.TipoSensor tipo) { this.tipo = tipo; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
