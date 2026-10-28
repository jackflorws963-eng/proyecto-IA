package pe.edu.unaj.smartstock.ia;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import pe.edu.unaj.smartstock.exception.NegocioException;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Memoria permanente del asistente: lo que las personas le ensenan ("recuerda que el proveedor del arroz
 * es Molinos del Sur"). En cada pregunta se recuperan los recuerdos mas parecidos (similitud por
 * palabras ponderada por rareza, tipo TF-IDF) y todas las preferencias.
 */
@Service
public class MemoriaIa {

    public enum Tipo { HECHO, PREFERENCIA }

    public record Recuerdo(Long id, Tipo tipo, String texto, String usuario, int usos, OffsetDateTime creado) { }

    static final int MAXIMO = 2000;

    private final JdbcTemplate jdbc;

    public MemoriaIa(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Recuerdo recordar(Tipo tipo, String texto, String usuario) {
        String limpio = texto == null ? "" : texto.trim();
        if (limpio.length() < 3) throw NegocioException.invalido("No hay nada que recordar");
        if (limpio.length() > 500) limpio = limpio.substring(0, 500);
        // No duplica lo que ya sabe
        for (Recuerdo r : todos()) {
            if (Texto.normalizar(r.texto()).equals(Texto.normalizar(limpio))) return r;
        }
        Integer total = jdbc.queryForObject("SELECT count(*) FROM ia_memoria", Integer.class);
        if (total != null && total >= MAXIMO) {
            // Olvida lo menos util: lo que nunca se uso y es mas antiguo
            jdbc.update("DELETE FROM ia_memoria WHERE id = (SELECT id FROM ia_memoria ORDER BY usos, creado_en LIMIT 1)");
        }
        Long id = jdbc.queryForObject("INSERT INTO ia_memoria (tipo, texto, username) VALUES (?, ?, ?) RETURNING id",
                Long.class, tipo.name(), limpio, usuario);
        return new Recuerdo(id, tipo, limpio, usuario, 0, OffsetDateTime.now());
    }

    public void olvidar(Long id, String usuario, boolean esAdmin) {
        int n = esAdmin ? jdbc.update("DELETE FROM ia_memoria WHERE id = ?", id)
                : jdbc.update("DELETE FROM ia_memoria WHERE id = ? AND username = ?", id, usuario);
        if (n == 0) throw NegocioException.noEncontrado("Recuerdo", id);
    }

    public List<Recuerdo> todos() {
        return jdbc.query("SELECT id, tipo, texto, username, usos, creado_en FROM ia_memoria ORDER BY creado_en DESC",
                (rs, i) -> new Recuerdo(rs.getLong(1), Tipo.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getObject(6, OffsetDateTime.class)));
    }

    /** Recuerdos utiles para responder: todas las preferencias y los hechos mas parecidos a la pregunta. */
    public List<Recuerdo> relevantes(String pregunta, int maximo) {
        List<Recuerdo> todos = todos();
        if (todos.isEmpty()) return List.of();
        // Rareza de cada rasgo: los que aparecen en pocos recuerdos pesan mas
        Map<String, Integer> frecuencia = new HashMap<>();
        Map<Long, Set<String>> rasgos = new HashMap<>();
        for (Recuerdo r : todos) {
            Set<String> s = new HashSet<>(Texto.rasgos(r.texto()));
            rasgos.put(r.id(), s);
            s.forEach(x -> frecuencia.merge(x, 1, Integer::sum));
        }
        Set<String> consulta = new HashSet<>(Texto.rasgos(pregunta));
        Map<Long, Double> puntaje = new HashMap<>();
        for (Recuerdo r : todos) {
            double p = 0;
            for (String x : rasgos.get(r.id())) {
                if (consulta.contains(x)) p += Math.log(1.0 + (double) todos.size() / frecuencia.get(x));
            }
            puntaje.put(r.id(), p);
        }
        List<Recuerdo> elegidos = todos.stream()
                .filter(r -> r.tipo() == Tipo.PREFERENCIA || puntaje.get(r.id()) > 0)
                .sorted(Comparator.comparing((Recuerdo r) -> r.tipo() == Tipo.PREFERENCIA ? 1e9 : puntaje.get(r.id())).reversed())
                .limit(maximo)
                .toList();
        if (!elegidos.isEmpty()) {
            jdbc.batchUpdate("UPDATE ia_memoria SET usos = usos + 1, usado_en = now() WHERE id = ?",
                    elegidos.stream().map(r -> new Object[]{r.id()}).toList());
        }
        return elegidos;
    }

    /** Dato aprendido que responde claramente la pregunta (comparten al menos 3 rasgos, p. ej. "proveedor", "arroz"). */
    public Optional<Recuerdo> respuestaDirecta(String pregunta) {
        Set<String> consulta = new HashSet<>(Texto.rasgos(pregunta));
        return todos().stream().filter(r -> r.tipo() == Tipo.HECHO)
                .map(r -> Map.entry(r, Texto.rasgos(r.texto()).stream().filter(consulta::contains).distinct().count()))
                .filter(e -> e.getValue() >= 3)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    public int cantidad() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ia_memoria", Integer.class);
        return n == null ? 0 : n;
    }
}
