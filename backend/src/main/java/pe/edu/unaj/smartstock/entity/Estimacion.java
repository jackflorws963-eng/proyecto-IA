package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Cantidad estimada a partir del peso, comparada con el stock registrado. */
@Entity
@Table(name = "estimacion")
public class Estimacion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "producto_id", nullable = false)
    private Producto producto;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ubicacion_id", nullable = false)
    private Ubicacion ubicacion;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lectura_id", nullable = false, unique = true)
    private Lectura lectura;

    @Column(name = "peso_neto_g", nullable = false, precision = 12, scale = 3)
    private BigDecimal pesoNetoG;

    @Column(name = "cantidad_estimada", nullable = false, precision = 10, scale = 2)
    private BigDecimal cantidadEstimada;

    @Column(name = "cantidad_registrada", nullable = false)
    private Integer cantidadRegistrada;

    @Column(name = "diferencia_pct", nullable = false, precision = 8, scale = 2)
    private BigDecimal diferenciaPct;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public Sensor getSensor() { return sensor; }
    public void setSensor(Sensor sensor) { this.sensor = sensor; }
    public Producto getProducto() { return producto; }
    public void setProducto(Producto producto) { this.producto = producto; }
    public Ubicacion getUbicacion() { return ubicacion; }
    public void setUbicacion(Ubicacion ubicacion) { this.ubicacion = ubicacion; }
    public Lectura getLectura() { return lectura; }
    public void setLectura(Lectura lectura) { this.lectura = lectura; }
    public BigDecimal getPesoNetoG() { return pesoNetoG; }
    public void setPesoNetoG(BigDecimal pesoNetoG) { this.pesoNetoG = pesoNetoG; }
    public BigDecimal getCantidadEstimada() { return cantidadEstimada; }
    public void setCantidadEstimada(BigDecimal cantidadEstimada) { this.cantidadEstimada = cantidadEstimada; }
    public Integer getCantidadRegistrada() { return cantidadRegistrada; }
    public void setCantidadRegistrada(Integer cantidadRegistrada) { this.cantidadRegistrada = cantidadRegistrada; }
    public BigDecimal getDiferenciaPct() { return diferenciaPct; }
    public void setDiferenciaPct(BigDecimal diferenciaPct) { this.diferenciaPct = diferenciaPct; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
