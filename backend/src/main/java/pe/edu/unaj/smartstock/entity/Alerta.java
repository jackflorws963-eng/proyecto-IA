package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "alerta")
public class Alerta {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Enums.TipoAlerta tipo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Enums.PrioridadAlerta prioridad;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private Enums.EstadoAlerta estado = Enums.EstadoAlerta.ABIERTA;

    /** Ej.: "TEMPERATURA:ubicacion:1". Una sola alerta no cerrada por clave. */
    @Column(name = "clave_dedup", nullable = false, length = 100)
    private String claveDedup;

    @Column(nullable = false, length = 255)
    private String mensaje;

    @Column(nullable = false)
    private Integer ocurrencias = 1;

    @Column(name = "primera_vez", nullable = false)
    private OffsetDateTime primeraVez = OffsetDateTime.now();

    @Column(name = "ultima_vez", nullable = false)
    private OffsetDateTime ultimaVez = OffsetDateTime.now();

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reconocida_por")
    private Usuario reconocidaPor;

    @Column(name = "reconocida_en")
    private OffsetDateTime reconocidaEn;

    @Column(name = "cerrada_en")
    private OffsetDateTime cerradaEn;

    public Long getId() { return id; }
    public Enums.TipoAlerta getTipo() { return tipo; }
    public void setTipo(Enums.TipoAlerta tipo) { this.tipo = tipo; }
    public Enums.PrioridadAlerta getPrioridad() { return prioridad; }
    public void setPrioridad(Enums.PrioridadAlerta prioridad) { this.prioridad = prioridad; }
    public Enums.EstadoAlerta getEstado() { return estado; }
    public void setEstado(Enums.EstadoAlerta estado) { this.estado = estado; }
    public String getClaveDedup() { return claveDedup; }
    public void setClaveDedup(String claveDedup) { this.claveDedup = claveDedup; }
    public String getMensaje() { return mensaje; }
    public void setMensaje(String mensaje) { this.mensaje = mensaje; }
    public Integer getOcurrencias() { return ocurrencias; }
    public void setOcurrencias(Integer ocurrencias) { this.ocurrencias = ocurrencias; }
    public OffsetDateTime getPrimeraVez() { return primeraVez; }
    public OffsetDateTime getUltimaVez() { return ultimaVez; }
    public void setUltimaVez(OffsetDateTime ultimaVez) { this.ultimaVez = ultimaVez; }
    public Usuario getReconocidaPor() { return reconocidaPor; }
    public void setReconocidaPor(Usuario reconocidaPor) { this.reconocidaPor = reconocidaPor; }
    public OffsetDateTime getReconocidaEn() { return reconocidaEn; }
    public void setReconocidaEn(OffsetDateTime reconocidaEn) { this.reconocidaEn = reconocidaEn; }
    public OffsetDateTime getCerradaEn() { return cerradaEn; }
    public void setCerradaEn(OffsetDateTime cerradaEn) { this.cerradaEn = cerradaEn; }
}
