package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "discrepancia")
public class Discrepancia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "estimacion_id", nullable = false)
    private Estimacion estimacion;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Enums.EstadoDiscrepancia estado = Enums.EstadoDiscrepancia.PENDIENTE;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "revisado_por")
    private Usuario revisadoPor;

    @Column(name = "revisado_en")
    private OffsetDateTime revisadoEn;

    @Column(length = 255)
    private String comentario;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Long getId() { return id; }
    public Estimacion getEstimacion() { return estimacion; }
    public void setEstimacion(Estimacion estimacion) { this.estimacion = estimacion; }
    public Sensor getSensor() { return sensor; }
    public void setSensor(Sensor sensor) { this.sensor = sensor; }
    public Enums.EstadoDiscrepancia getEstado() { return estado; }
    public void setEstado(Enums.EstadoDiscrepancia estado) { this.estado = estado; }
    public Usuario getRevisadoPor() { return revisadoPor; }
    public void setRevisadoPor(Usuario revisadoPor) { this.revisadoPor = revisadoPor; }
    public OffsetDateTime getRevisadoEn() { return revisadoEn; }
    public void setRevisadoEn(OffsetDateTime revisadoEn) { this.revisadoEn = revisadoEn; }
    public String getComentario() { return comentario; }
    public void setComentario(String comentario) { this.comentario = comentario; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
