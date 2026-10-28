package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "calibracion")
public class Calibracion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal factor;

    @Column(name = "tara_g", nullable = false, precision = 12, scale = 3)
    private BigDecimal taraG = BigDecimal.ZERO;

    @Column(name = "peso_patron_g", precision = 12, scale = 3)
    private BigDecimal pesoPatronG;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(nullable = false)
    private boolean vigente = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public Sensor getSensor() { return sensor; }
    public void setSensor(Sensor sensor) { this.sensor = sensor; }
    public BigDecimal getFactor() { return factor; }
    public void setFactor(BigDecimal factor) { this.factor = factor; }
    public BigDecimal getTaraG() { return taraG; }
    public void setTaraG(BigDecimal taraG) { this.taraG = taraG; }
    public BigDecimal getPesoPatronG() { return pesoPatronG; }
    public void setPesoPatronG(BigDecimal pesoPatronG) { this.pesoPatronG = pesoPatronG; }
    public Usuario getUsuario() { return usuario; }
    public void setUsuario(Usuario usuario) { this.usuario = usuario; }
    public boolean isVigente() { return vigente; }
    public void setVigente(boolean vigente) { this.vigente = vigente; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
