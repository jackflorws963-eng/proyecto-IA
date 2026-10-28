package pe.edu.unaj.smartstock.entity;

import jakarta.persistence.*;

/** Serie y ultimo correlativo emitido de cada tipo de comprobante (B001, F001, NV01). */
@Entity
@Table(name = "serie_comprobante")
public class SerieComprobante {
    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 15)
    private Enums.TipoComprobante tipo;

    @Column(nullable = false, unique = true, length = 4)
    private String serie;

    @Column(nullable = false)
    private Integer ultimo = 0;

    public Enums.TipoComprobante getTipo() { return tipo; }
    public String getSerie() { return serie; }
    public Integer getUltimo() { return ultimo; }
    public void setUltimo(Integer ultimo) { this.ultimo = ultimo; }
}
