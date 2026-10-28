package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ubicacion")
public class Ubicacion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String nombre;

    @Column(length = 255)
    private String descripcion;

    @Column(name = "temp_min", nullable = false, precision = 5, scale = 2)
    private BigDecimal tempMin;

    @Column(name = "temp_max", nullable = false, precision = 5, scale = 2)
    private BigDecimal tempMax;

    @Column(name = "hum_min", nullable = false, precision = 5, scale = 2)
    private BigDecimal humMin;

    @Column(name = "hum_max", nullable = false, precision = 5, scale = 2)
    private BigDecimal humMax;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public BigDecimal getHumMin() { return humMin; }
    public void setHumMin(BigDecimal humMin) { this.humMin = humMin; }
    public BigDecimal getHumMax() { return humMax; }
    public void setHumMax(BigDecimal humMax) { this.humMax = humMax; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
