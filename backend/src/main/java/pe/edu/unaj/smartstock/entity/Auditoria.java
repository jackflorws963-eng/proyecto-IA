package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import java.time.OffsetDateTime;

@Entity
@Immutable
@Table(name = "auditoria")
public class Auditoria {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 50)
    private String username;

    @Column(nullable = false, length = 40)
    private String accion;

    @Column(nullable = false, length = 40)
    private String entidad;

    @Column(name = "entidad_id")
    private Long entidadId;

    @Column(columnDefinition = "TEXT")
    private String detalle;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public Auditoria() { }

    public Auditoria(String username, String accion, String entidad, Long entidadId, String detalle) {
        this.username = username;
        this.accion = accion;
        this.entidad = entidad;
        this.entidadId = entidadId;
        this.detalle = detalle;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getAccion() { return accion; }
    public String getEntidad() { return entidad; }
    public Long getEntidadId() { return entidadId; }
    public String getDetalle() { return detalle; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
