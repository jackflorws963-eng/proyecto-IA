package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "dispositivo")
public class Dispositivo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String codigo;

    @Column(nullable = false, length = 80)
    private String nombre;

    /** Hash BCrypt de la clave del dispositivo. La clave en claro nunca se guarda. */
    @Column(name = "api_key_hash", nullable = false, length = 100)
    private String apiKeyHash;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "ubicacion_id", nullable = false)
    private Ubicacion ubicacion;

    @Column(nullable = false)
    private boolean activo = true;

    @Column(name = "ultimo_contacto")
    private OffsetDateTime ultimoContacto;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Ventilador conectado al rele del ESP32 (ver VentiladorService). */
    @Enumerated(EnumType.STRING)
    @Column(name = "ventilador_modo", nullable = false, length = 10)
    private Enums.ModoVentilador ventiladorModo = Enums.ModoVentilador.AUTO;

    /** Estado que el servidor ordena al ESP32. */
    @Column(name = "ventilador_encendido", nullable = false)
    private boolean ventiladorEncendido;

    @Column(name = "ventilador_cambio")
    private OffsetDateTime ventiladorCambio;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }
    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public Ubicacion getUbicacion() { return ubicacion; }
    public void setUbicacion(Ubicacion ubicacion) { this.ubicacion = ubicacion; }
    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }
    public OffsetDateTime getUltimoContacto() { return ultimoContacto; }
    public void setUltimoContacto(OffsetDateTime ultimoContacto) { this.ultimoContacto = ultimoContacto; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Enums.ModoVentilador getVentiladorModo() { return ventiladorModo; }
    public void setVentiladorModo(Enums.ModoVentilador ventiladorModo) { this.ventiladorModo = ventiladorModo; }
    public boolean isVentiladorEncendido() { return ventiladorEncendido; }
    public void setVentiladorEncendido(boolean ventiladorEncendido) { this.ventiladorEncendido = ventiladorEncendido; }
    public OffsetDateTime getVentiladorCambio() { return ventiladorCambio; }
    public void setVentiladorCambio(OffsetDateTime ventiladorCambio) { this.ventiladorCambio = ventiladorCambio; }
}
