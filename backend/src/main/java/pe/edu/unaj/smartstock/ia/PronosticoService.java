package pe.edu.unaj.smartstock.ia;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pronostico de demanda y agotamiento por producto, aprendido de las salidas reales (ventas incluidas).
 * Usa suavizado exponencial simple sobre la demanda diaria de los ultimos 60 dias: da mas peso a lo
 * reciente, asi el pronostico se adapta solo cuando cambia el ritmo de ventas. Se recalcula en cada consulta.
 */
@Service
public class PronosticoService {

    static final ZoneId ZONA = ZoneId.of("America/Lima");
    static final int DIAS_HISTORIA = 60;
    /** Peso de la observacion mas reciente (0..1): mas alto = reacciona mas rapido a los cambios. */
    static final double ALFA = 0.3;
    /** Dias de cobertura que se buscan al sugerir cuanto reponer. */
    static final int DIAS_COBERTURA = 14;

    public record Pronostico(Long productoId, String codigo, String nombre, long stock, int stockMinimo,
                             BigDecimal demandaDiaria, Integer diasRestantes, LocalDate fechaAgotamiento,
                             long sugeridoReponer, int diasConDatos, String confianza) { }

    private final JdbcTemplate jdbc;

    public PronosticoService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Todos los productos activos, primero los que se agotan antes. */
    public List<Pronostico> calcular() {
        LocalDate hoy = LocalDate.now(ZONA);
        LocalDate inicio = hoy.minusDays(DIAS_HISTORIA - 1L);

        // Salidas por producto y dia (las ventas generan movimientos SALIDA)
        Map<Long, Map<LocalDate, Long>> salidas = new HashMap<>();
        jdbc.query("""
                SELECT producto_id, (created_at AT TIME ZONE 'America/Lima')::date AS dia, sum(cantidad)
                FROM movimiento
                WHERE tipo = 'SALIDA' AND created_at >= ?
                GROUP BY 1, 2""",
                rs -> {
                    salidas.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                            .put(rs.getObject(2, LocalDate.class), rs.getLong(3));
                },
                inicio.atStartOfDay(ZONA).toOffsetDateTime());

        List<Pronostico> r = new ArrayList<>();
        jdbc.query("""
                SELECT p.id, p.codigo, p.nombre, p.stock_minimo, coalesce(sum(i.cantidad), 0)
                FROM producto p LEFT JOIN inventario i ON i.producto_id = p.id
                WHERE p.activo
                GROUP BY p.id, p.codigo, p.nombre, p.stock_minimo""",
                rs -> {
                    r.add(pronosticar(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5),
                            salidas.getOrDefault(rs.getLong(1), Map.of()), hoy));
                });
        r.sort(Comparator.comparing((Pronostico p) -> p.diasRestantes() == null ? Integer.MAX_VALUE : p.diasRestantes())
                .thenComparing(Pronostico::codigo));
        return r;
    }

    static Pronostico pronosticar(Long id, String codigo, String nombre, int stockMinimo, long stock,
                                  Map<LocalDate, Long> porDia, LocalDate hoy) {
        if (porDia.isEmpty()) {
            return new Pronostico(id, codigo, nombre, stock, stockMinimo, BigDecimal.ZERO, null, null,
                    Math.max(stockMinimo - stock, 0), 0, "SIN_DATOS");
        }
        // La serie empieza el primer dia con salidas: un producto nuevo no se subestima con ceros previos
        LocalDate primero = porDia.keySet().stream().min(LocalDate::compareTo).orElse(hoy);
        double nivel = porDia.get(primero);
        int dias = 1;
        for (LocalDate d = primero.plusDays(1); !d.isAfter(hoy); d = d.plusDays(1)) {
            nivel = ALFA * porDia.getOrDefault(d, 0L) + (1 - ALFA) * nivel;
            dias++;
        }
        BigDecimal demanda = BigDecimal.valueOf(nivel).setScale(2, RoundingMode.HALF_UP);
        Integer restantes = nivel < 0.01 ? null : (int) Math.floor(stock / nivel);
        long reponer = Math.max(Math.round(Math.ceil(nivel * DIAS_COBERTURA)) + stockMinimo - stock, 0);
        String confianza = dias >= 21 ? "ALTA" : dias >= 7 ? "MEDIA" : "BAJA";
        return new Pronostico(id, codigo, nombre, stock, stockMinimo, demanda, restantes,
                restantes == null ? null : hoy.plusDays(restantes), reponer, dias, confianza);
    }
}
