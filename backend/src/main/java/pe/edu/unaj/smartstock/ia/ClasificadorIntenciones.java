package pe.edu.unaj.smartstock.ia;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ClasificadorIntenciones {

    private static final Logger log = LoggerFactory.getLogger(ClasificadorIntenciones.class);

    public static final Map<String, String> ETIQUETAS = new LinkedHashMap<>();

    private static final Map<String, List<String>> SEMILLAS = new LinkedHashMap<>();

    static final double SUAVIZADO = 0.1;

    static {
        semilla("diagnostico", "Ver problemas actuales", "que errores hay", "hay algun problema", "como esta todo",
                "diagnostico del sistema", "que alertas hay", "novedades", "revisa el sistema", "todo bien", "que pasa",
                "hay algo raro", "algo anda mal");
        semilla("reparar", "Reparar problemas", "repara los problemas", "arregla todo", "soluciona los errores",
                "corrige las fallas", "resuelve los problemas", "haz algo con las alertas");
        semilla("reconocer_alertas", "Marcar alertas como vistas", "marca las alertas como vistas", "ya vi las alertas",
                "reconoce las alertas", "marcar vistas");
        semilla("controlar_ventilador", "Controlar el ventilador", "enciende el ventilador", "apaga el ventilador",
                "ventilador en automatico", "prende el ventilador", "como esta el ventilador", "activa la ventilacion");
        semilla("consultar_ambiente", "Temperatura y humedad", "como esta la temperatura", "cuanta humedad hay",
                "hace calor en el almacen", "temperatura del almacen", "clima del almacen", "cuantos grados hay");
        semilla("resumen_ventas", "Resumen de ventas", "cuanto vendimos hoy", "ventas del dia", "como van las ventas",
                "total vendido en el mes", "cuanto se recaudo", "resumen de caja", "ingresos de hoy");
        semilla("consultar_ventas", "Ultimas ventas", "ultimas ventas", "lista de ventas", "que boletas se emitieron",
                "historial de ventas", "ventas de la semana");
        semilla("estado_dispositivos", "Estado de dispositivos", "estan conectados los dispositivos", "estado del esp32",
                "los sensores funcionan", "hay dispositivos desconectados", "conexion wifi del esp32");
        semilla("consultar_discrepancias", "Discrepancias de peso", "hay discrepancias", "diferencias de peso",
                "discrepancias pendientes", "el peso no coincide");
        semilla("consultar_existencias", "Consultar stock", "cuanto stock hay de arroz", "existencias del producto",
                "cuantas unidades quedan", "stock del almacen", "inventario actual", "cuanto queda de pernos",
                "que productos tienen stock bajo", "productos bajo el minimo", "cuantas bolsas quedan",
                "cuantas unidades de arroz quedan", "quedan pernos", "tenemos stock de cables", "hay arroz en el almacen");
        semilla("buscar_productos", "Buscar productos", "busca el producto", "lista de productos", "que productos hay",
                "precio del arroz", "cuanto cuesta", "catalogo de productos", "codigo del producto");
        semilla("pronosticar_agotamiento", "Predecir agotamiento", "cuando se acaba el arroz", "que productos se van a agotar",
                "pronostico de demanda", "prediccion de stock", "cuanto debo pedir", "cuando debo reponer",
                "que debo comprar", "proyeccion de ventas", "dias de stock restantes");
        semilla("consultar_movimientos", "Historial de movimientos", "ultimos movimientos", "historial de entradas",
                "quien saco productos", "movimientos de hoy", "salidas recientes");
        semilla("registrar_movimiento", "Registrar entrada o salida", "registra una entrada de 10 arroz",
                "ingresa 20 pernos al almacen", "da salida a 5 cables", "llegaron 30 bolsas de arroz",
                "saca 3 unidades", "transfiere 5 productos al almacen b", "entrada de mercaderia", "agrega stock");
        semilla("registrar_venta", "Registrar una venta", "vende 2 arroz", "registra una venta", "cobra 3 pernos",
                "nueva venta de 1 cable", "hacer una boleta", "vendi 4 bolsas de arroz");
        semilla("abrir_pantalla", "Abrir una pantalla", "abre ventas", "ir a inventario", "muestrame el dashboard",
                "llevame a productos", "abre la pantalla de alertas", "ir a dispositivos", "abrir punto de venta");
        semilla("descargar_reporte", "Descargar reporte", "descarga el reporte de existencias", "exporta las ventas",
                "reporte de movimientos en excel", "genera un reporte csv", "bajar reporte de alertas");
        semilla("imprimir", "Imprimir", "imprime esta pantalla", "imprimir", "manda a imprimir");
        semilla("recordar", "Aprender algo", "recuerda que el proveedor del arroz es molinos",
                "aprende que los lunes llega mercaderia", "anota que", "no olvides que", "guarda en tu memoria que");
        semilla("que_recuerdas", "Ver lo aprendido", "que recuerdas", "que has aprendido", "que sabes de mi",
                "muestrame tu memoria", "que te enseñe");
        semilla("ayuda", "Ayuda", "ayuda", "que puedes hacer", "que sabes hacer", "comandos", "como funcionas");
        semilla("saludo", "Saludo", "hola", "buenos dias", "buenas tardes", "que tal", "gracias", "genial");
    }

    private static void semilla(String intencion, String etiqueta, String... frases) {
        ETIQUETAS.put(intencion, etiqueta);
        SEMILLAS.put(intencion, List.of(frases));
    }

    public record Prediccion(String intencion, double confianza) { }

    private final JdbcTemplate jdbc;
    private final Map<String, Map<String, Integer>> conteos = new HashMap<>();
    private final Map<String, Integer> totales = new HashMap<>();
    private final Set<String> vocabulario = new HashSet<>();
    private volatile boolean cargado;
    private int aprendidos;

    public ClasificadorIntenciones(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized List<Prediccion> clasificar(String texto) {
        cargar();
        List<String> rasgos = Texto.rasgos(texto).stream().filter(vocabulario::contains).toList();
        if (rasgos.isEmpty()) return List.of();
        Map<String, Double> logp = new HashMap<>();
        int v = vocabulario.size();
        for (String c : conteos.keySet()) {
            double s = 0;
            Map<String, Integer> cc = conteos.get(c);
            for (String r : rasgos) s += Math.log((cc.getOrDefault(r, 0) + SUAVIZADO) / (totales.get(c) + SUAVIZADO * v));
            logp.put(c, s);
        }
        double max = logp.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double suma = logp.values().stream().mapToDouble(x -> Math.exp(x - max)).sum();
        return logp.entrySet().stream()
                .map(e -> new Prediccion(e.getKey(), Math.exp(e.getValue() - max) / suma))
                .sorted(Comparator.comparingDouble(Prediccion::confianza).reversed())
                .limit(3)
                .toList();
    }

    public synchronized void aprender(String texto, String intencion, String origen, String usuario) {
        if (!ETIQUETAS.containsKey(intencion) || texto == null || texto.isBlank()) return;
        cargar();
        String limpio = texto.trim().length() > 500 ? texto.trim().substring(0, 500) : texto.trim();
        int n = jdbc.update("INSERT INTO ia_ejemplo (texto, intencion, origen, username) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (texto, intencion) DO NOTHING", limpio, intencion, origen, usuario);
        if (n > 0) {
            sumar(intencion, limpio, "USUARIO".equals(origen) ? 2 : 1);
            aprendidos++;
        }
    }

    public synchronized int aprendidos() {
        cargar();
        return aprendidos;
    }

    private void cargar() {
        if (cargado) return;
        SEMILLAS.forEach((c, frases) -> frases.forEach(f -> sumar(c, f, 1)));
        try {
            jdbc.query("SELECT texto, intencion, origen FROM ia_ejemplo", rs -> {
                if (ETIQUETAS.containsKey(rs.getString(2))) {
                    sumar(rs.getString(2), rs.getString(1), "USUARIO".equals(rs.getString(3)) ? 2 : 1);
                    aprendidos++;
                }
            });
        } catch (DataAccessException e) {
            log.warn("No se pudieron cargar los ejemplos aprendidos: {}", e.getMessage());
        }
        cargado = true;
    }

    private void sumar(String intencion, String texto, int peso) {
        Map<String, Integer> cc = conteos.computeIfAbsent(intencion, k -> new HashMap<>());
        for (String r : Texto.rasgos(texto)) {
            cc.merge(r, peso, Integer::sum);
            totales.merge(intencion, peso, Integer::sum);
            vocabulario.add(r);
        }
        totales.putIfAbsent(intencion, 0);
    }

    public static List<String> intenciones() {
        return new ArrayList<>(ETIQUETAS.keySet());
    }
}
