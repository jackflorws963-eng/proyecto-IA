package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Immutable
@Table(name = "lectura")
public class Lectura {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal valor;

    @Column(nullable = false)
    private Long seq;

    @Column(name = "medido_en", nullable = false)
    private OffsetDateTime medidoEn;

    @Column(name = "recibido_en", nullable = false)
    private OffsetDateTime recibidoEn = OffsetDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.EstadoLectura estado;

    @Column(length = 255)
    private String observacion;

    public Long getId() { return id; }
    public Sensor getSensor() { return sensor; }
    public void setSensor(Sensor sensor) { this.sensor = sensor; }
    public BigDecimal getValor() { return valor; }
    public void setValor(BigDecimal valor) { this.valor = valor; }
    public Long getSeq() { return seq; }
    public void setSeq(Long seq) { this.seq = seq; }
    public OffsetDateTime getMedidoEn() { return medidoEn; }
    public void setMedidoEn(OffsetDateTime medidoEn) { this.medidoEn = medidoEn; }
    public OffsetDateTime getRecibidoEn() { return recibidoEn; }
    public void setRecibidoEn(OffsetDateTime recibidoEn) { this.recibidoEn = recibidoEn; }
    public Enums.EstadoLectura getEstado() { return estado; }
    public void setEstado(Enums.EstadoLectura estado) { this.estado = estado; }
    public String getObservacion() { return observacion; }
    public void setObservacion(String observacion) { this.observacion = observacion; }
}
