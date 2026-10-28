package pe.edu.unaj.smartstock.ia;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import pe.edu.unaj.smartstock.dto.Dtos.ProductoResponse;
import pe.edu.unaj.smartstock.dto.Dtos.RespuestaAsistente;
import pe.edu.unaj.smartstock.dto.Dtos.UbicacionResponse;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.ia.Herramientas.Args;
import pe.edu.unaj.smartstock.ia.Herramientas.Contexto;
import pe.edu.unaj.smartstock.ia.Herramientas.Herramienta;
import pe.edu.unaj.smartstock.ia.Herramientas.Resultado;
import pe.edu.unaj.smartstock.service.AsistenteService;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Asistente de inteligencia artificial de SmartStock. Funciona sin internet con dos motores:
 * <ol>
 *   <li><b>Modelo de lenguaje local</b> (Ollama en esta PC): entiende lenguaje libre y usa las herramientas
 *       del programa (consultas, operaciones, pantallas, memoria).</li>
 *   <li><b>Motor propio</b> (siempre disponible): clasificador de intenciones que aprende en linea y extrae
 *       productos, cantidades y ubicaciones de la frase.</li>
 * </ol>
 * Aprende en la marcha: guarda lo que le ensenan (memoria), aprende frases nuevas cuando la persona lo corrige
 * y cada vez que el modelo local resuelve una pregunta (el motor propio aprende de el), y pronostica la demanda
 * con las ventas reales. Todo cambio de datos se propone y solo se ejecuta con la confirmacion de la persona.
 */
@Service
public class AsistenteIa {

    private static final Logger log = LoggerFactory.getLogger(AsistenteIa.class);

    static final int MAX_TURNOS_HERRAMIENTAS = 6;
    static final int MAX_HISTORIAL = 40;
    static final int MAX_CONTEXTO = 12;
    static final double CONFIANZA_MINIMA = 0.45;
    static final double VENTAJA_MINIMA = 2.5;
    /** Para proponer algo que modifica datos el motor propio exige mas certeza. */
    static final double CONFIANZA_OPERACION = 0.8;
    static final Duration VIGENCIA_PROPUESTA = Duration.ofMinutes(15);
    private static final String ACCIONES_DIAGNOSTICO = "_acciones_diagnostico";

    private static final Pattern SI = Pattern.compile("^(si|sí|claro|dale|ok|okey|confirmo|hazlo|adelante|de acuerdo|correcto)\\b.*");
    private static final Pattern NO = Pattern.compile("^(no|cancela|cancelar|mejor no|dejalo|olvidalo)\\b.*");
    private static final Pattern RECORDAR = Pattern.compile(
            "^(recuerda|aprende|anota|apunta|memoriza|guarda en tu memoria|no olvides|ten en cuenta)( que| esto)?[:,]?\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERO = Pattern.compile("\\b(\\d{1,6})\\b");
    private static final Pattern RECIBIDO = Pattern.compile(
            "\\b(me dieron|me dio|pago con|paga con|pagan con|recibi|recibo|billete de)( s)?\\s+(\\d+(?:[.,]\\d{1,2})?)");

    /** Frase que entiende el diagnostico por reglas para cada intencion que este resuelve. */
    private static final Map<String, String> FRASE_DIAGNOSTICO = Map.of(
            "diagnostico", "que errores hay", "reparar", "repara los problemas", "reconocer_alertas", "marca como vistas",
            "consultar_ambiente", "como esta la temperatura", "estado_dispositivos", "estado de los dispositivos",
            "consultar_discrepancias", "discrepancias", "saludo", "hola");

    public record Accion(String id, String descripcion) { }

    public record Opcion(String intencion, String etiqueta) { }

    /** motor: MODELO (lenguaje local) o PROPIO (clasificador). opciones: "¿que quisiste decir?" para aprender. */
    public record Respuesta(String texto, String motor, List<Accion> porConfirmar, String navegar, String descargar,
                            boolean imprimir, List<Opcion> opciones) { }

    public record Mensaje(String rol, String texto, OffsetDateTime fecha) { }

    public record Estado(String motor, String modelo, boolean modeloDisponible, String detalle,
                         int frasesAprendidas, int recuerdos, int herramientas) { }

    private record Pendiente(String id, String usuario, String herramienta, Map<String, Object> args,
                             String descripcion, OffsetDateTime creado) { }

    private final Herramientas herramientas;
    private final ClienteOllama modelo;
    private final ClasificadorIntenciones clasificador;
    private final MemoriaIa memoria;
    private final AsistenteService diagnostico;
    private final ObjectMapper json;

    private final Map<String, Pendiente> pendientes = new ConcurrentHashMap<>();
    private final Map<String, Deque<Map<String, Object>>> contexto = new ConcurrentHashMap<>();
    private final Map<String, Deque<Mensaje>> historial = new ConcurrentHashMap<>();

    public AsistenteIa(Herramientas herramientas, ClienteOllama modelo, ClasificadorIntenciones clasificador,
                       MemoriaIa memoria, AsistenteService diagnostico, ObjectMapper json) {
        this.herramientas = herramientas;
        this.modelo = modelo;
        this.clasificador = clasificador;
        this.memoria = memoria;
        this.diagnostico = diagnostico;
        this.json = json;
    }

    // =====================================================================================  API

    public Respuesta mensaje(String texto, Authentication auth) {
        String limpio = texto == null ? "" : texto.trim();
        anotar(auth, "usuario", limpio);
        String t = Texto.normalizar(limpio);
        List<Pendiente> mias = pendientesDe(auth.getName());

        Respuesta r;
        if (!mias.isEmpty() && SI.matcher(t).matches()) {
            r = confirmarTodas(mias, auth);
        } else if (!mias.isEmpty() && NO.matcher(t).matches()) {
            mias.forEach(p -> pendientes.remove(p.id()));
            r = simple("De acuerdo, no hago nada.", motorActual());
        } else if (t.isBlank()) {
            r = simple("No escuché nada. ¿Puedes repetirlo?", motorActual());
        } else if (RECORDAR.matcher(limpio).matches()) {
            // Ensenar es determinista: se guarda siempre, sin depender de que el modelo llame a la herramienta
            r = conMotorPropio(limpio, auth);
            recordarTurno(auth.getName(), limpio, r.texto());
        } else if (modelo.disponible()) {
            try {
                r = conModelo(limpio, auth);
            } catch (IOException | RuntimeException e) {
                log.warn("El modelo local fallo, respondo con el motor propio: {}", e.getMessage());
                r = conMotorPropio(limpio, auth);
            }
        } else {
            r = conMotorPropio(limpio, auth);
        }
        anotar(auth, "ia", r.texto());
        return r;
    }

    public Respuesta confirmar(String id, Authentication auth) {
        Pendiente p = pendientes.get(id);
        if (p == null || !p.usuario().equals(auth.getName())) {
            return simple("Esa propuesta ya no está vigente. Pídemelo de nuevo.", motorActual());
        }
        Respuesta r = confirmarTodas(List.of(p), auth);
        anotar(auth, "ia", r.texto());
        return r;
    }

    public Respuesta cancelar(String id, Authentication auth) {
        Pendiente p = pendientes.get(id);
        if (p != null && p.usuario().equals(auth.getName())) pendientes.remove(id);
        Respuesta r = simple("Cancelado: " + (p == null ? "la acción" : minuscula(p.descripcion())) + ".", motorActual());
        anotar(auth, "ia", r.texto());
        return r;
    }

    /** La persona corrige al asistente: aprende la frase y la resuelve con la intencion correcta. */
    public Respuesta aprender(String texto, String intencion, Authentication auth) {
        if (!ClasificadorIntenciones.ETIQUETAS.containsKey(intencion)) throw NegocioException.invalido("Intención desconocida");
        clasificador.aprender(texto, intencion, "USUARIO", auth.getName());
        Respuesta r = resolverIntencion(intencion, texto, auth);
        r = new Respuesta("Aprendido: la próxima vez entenderé «" + texto + "». " + r.texto(), r.motor(), r.porConfirmar(),
                r.navegar(), r.descargar(), r.imprimir(), List.of());
        anotar(auth, "ia", r.texto());
        return r;
    }

    public List<Mensaje> historial(Authentication auth) {
        Deque<Mensaje> h = historial.get(auth.getName());
        if (h == null) return List.of();
        synchronized (h) {
            return List.copyOf(h);
        }
    }

    public List<Accion> pendientes(Authentication auth) {
        return pendientesDe(auth.getName()).stream().map(p -> new Accion(p.id(), p.descripcion())).toList();
    }

    public void reiniciar(Authentication auth) {
        historial.remove(auth.getName());
        contexto.remove(auth.getName());
        pendientesDe(auth.getName()).forEach(p -> pendientes.remove(p.id()));
    }

    public Estado estado() {
        boolean listo = modelo.disponible();
        return new Estado(listo ? "MODELO" : "PROPIO", modelo.modelo(), listo, modelo.motivo(),
                clasificador.aprendidos(), memoria.cantidad(), herramientas.todas().size());
    }

    // =====================================================================================  MODELO LOCAL

    private Respuesta conModelo(String texto, Authentication auth) throws IOException {
        Contexto ctx = new Contexto(auth);
        // El motor propio analiza cada parte de la pregunta y le sugiere herramientas al modelo
        Map<String, String> pistas = pistasPorPartes(texto);
        List<Map<String, Object>> mensajes = new ArrayList<>();
        mensajes.add(Map.of("role", "system", "content", instrucciones(texto, ctx, pistas.keySet())));
        mensajes.addAll(contextoDe(auth.getName()));
        mensajes.add(Map.of("role", "user", "content", texto));

        List<Accion> propuestas = new ArrayList<>();
        Set<String> usadas = new LinkedHashSet<>();
        List<String> resultados = new ArrayList<>();
        String respuesta = "";
        List<Map<String, Object>> definiciones = herramientas.definiciones(ctx.esAdmin());

        boolean corregido = false;
        for (int turno = 0; turno < MAX_TURNOS_HERRAMIENTAS; turno++) {
            JsonNode m = modelo.chat(mensajes, definiciones);
            JsonNode llamadas = m.path("tool_calls");
            if (!llamadas.isArray() || llamadas.isEmpty()) {
                respuesta = m.path("content").asText("").trim();
                // A veces el modelo escribe la llamada como texto (recordar{"texto":...}): se ejecuta igual
                List<Map.Entry<String, Map<String, Object>>> escritas = llamadasEscritas(respuesta);
                if (!escritas.isEmpty()) {
                    StringBuilder salidas = new StringBuilder();
                    for (var e : escritas) {
                        usadas.add(e.getKey());
                        String salida = usarHerramienta(e.getKey(), e.getValue(), ctx, propuestas);
                        resultados.add(salida);
                        salidas.append(e.getKey()).append(": ").append(salida).append('\n');
                    }
                    mensajes.add(Map.of("role", "assistant", "content", respuesta));
                    mensajes.add(Map.of("role", "user", "content", "(Resultados de las herramientas)\n" + salidas
                            + "Ahora responde al usuario con estos datos, sin escribir llamadas a herramientas."));
                    respuesta = "";
                    continue;
                }
                // El modelo dice que propuso o hizo algo sin haber llamado a la herramienta: se le exige hacerlo
                if (propuestas.isEmpty() && usadas.isEmpty() && afirmaAccion(respuesta) && !corregido) {
                    corregido = true;
                    log.info("IA: afirmo una accion sin usar herramientas, se le pide corregir: {}", respuesta);
                    mensajes.add(Map.of("role", "assistant", "content", respuesta));
                    mensajes.add(Map.of("role", "user", "content", """
                            (Mensaje del sistema) No llamaste a ninguna herramienta, asi que no se propuso nada y el \
                            usuario no tiene boton para confirmar. Si el usuario pidio una accion, llama AHORA a la \
                            herramienta correspondiente. No inventes precios ni datos: los da el sistema."""));
                    continue;
                }
                break;
            }
            Map<String, Object> delModelo = new LinkedHashMap<>();
            delModelo.put("role", "assistant");
            delModelo.put("content", m.path("content").asText(""));
            delModelo.put("tool_calls", json.convertValue(llamadas, new TypeReference<List<Object>>() { }));
            mensajes.add(delModelo);
            for (JsonNode llamada : llamadas) {
                String nombre = llamada.path("function").path("name").asText();
                Map<String, Object> args = argumentos(llamada.path("function").path("arguments"));
                usadas.add(nombre);
                String salida = usarHerramienta(nombre, args, ctx, propuestas);
                resultados.add(salida);
                mensajes.add(Map.of("role", "tool", "tool_name", nombre, "content", salida));
            }
        }
        String motor = "MODELO";
        // Se pidio una operacion y el modelo no la propuso: nunca se confia en su texto ("registramos la venta…").
        // La propuesta la arma el motor propio con los datos reales del sistema.
        Optional<String> operacion = pistas.keySet().stream()
                .filter(n -> herramientas.buscar(n).map(Herramienta::escritura).orElse(false)).findFirst();
        if (propuestas.isEmpty() && operacion.isPresent()) {
            log.info("IA: el modelo no propuso la operacion {}; la prepara el motor propio", operacion.get());
            Respuesta local = resolverIntencion(operacion.get(), texto, auth);
            recordarTurno(auth.getName(), texto, local.texto());
            return local;
        }
        // Respaldo: si el modelo no consulto nada, responde el motor propio con datos reales
        if (usadas.isEmpty() && propuestas.isEmpty()) {
            List<String> partes = new ArrayList<>();
            for (var p : pistas.entrySet()) {
                if (herramientas.buscar(p.getKey()).map(Herramienta::escritura).orElse(true)) continue;
                Respuesta local = resolverIntencion(p.getKey(), p.getValue(), auth);
                partes.add(local.texto());
                if (local.navegar() != null) ctx.navegar = local.navegar();
                if (local.descargar() != null) ctx.descargar = local.descargar();
            }
            if (!partes.isEmpty()) {
                log.info("IA: el modelo no uso herramientas; responde el motor propio con {}", pistas.keySet());
                respuesta = String.join(" ", partes);
                motor = "PROPIO";
            }
        }
        if (respuesta.isBlank()) {
            respuesta = propuestas.isEmpty() ? String.join(" ", resultados) : "Te propongo lo siguiente. ¿Lo confirmo?";
        }
        // Ultima barrera: nunca mostrar "propuse…" o "registré…" si no hay nada propuesto ni ejecutado
        // (la memoria si se guarda al instante: "registré en mi memoria" es verdad)
        if (propuestas.isEmpty() && usadas.isEmpty() && afirmaAccion(respuesta)) {
            respuesta = "No pude preparar esa acción. ¿Me la repites indicando el producto y la cantidad?";
        }
        if (propuestas.isEmpty()) respuesta = sinMuletillas(respuesta);
        // El motor propio aprende de lo que resolvio el modelo (una sola herramienta = intencion clara)
        if (usadas.size() == 1 && ClasificadorIntenciones.ETIQUETAS.containsKey(usadas.iterator().next())) {
            clasificador.aprender(texto, usadas.iterator().next(), "MODELO", auth.getName());
        }
        recordarTurno(auth.getName(), texto, respuesta);
        return new Respuesta(respuesta, motor, propuestas, ctx.navegar, ctx.descargar, ctx.imprimir, List.of());
    }

    /**
     * Intencion clara de cada parte de la pregunta ("¿cuánto vendimos hoy y para cuántos días alcanza el arroz?"
     * -> resumen_ventas, pronosticar_agotamiento), con el fragmento que la origino.
     */
    Map<String, String> pistasPorPartes(String texto) {
        Map<String, String> r = new LinkedHashMap<>();
        List<String> partes = new ArrayList<>(List.of(texto));
        for (String p : texto.split("[?¿.;,]|\\s+y\\s+")) if (p.trim().length() > 3) partes.add(p.trim());
        for (String parte : partes) {
            List<ClasificadorIntenciones.Prediccion> p = clasificador.clasificar(parte);
            if (!seguro(p)) continue;
            Optional<Herramienta> h = herramientas.buscar(p.get(0).intencion());
            // Una operacion solo se considera pedida con alta certeza
            if (h.isPresent() && (!h.get().escritura() || p.get(0).confianza() >= CONFIANZA_OPERACION)) {
                r.putIfAbsent(p.get(0).intencion(), parte);
            }
        }
        return r;
    }

    private static final Pattern LLAMADA_ESCRITA = Pattern.compile("([a-z_]{4,40})\\s*(\\{.*?\\})(?=\\s|$)", Pattern.DOTALL);
    private static final Pattern LLAMADA_JSON = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([a-z_]+)\"\\s*,\\s*\"arguments\"\\s*:\\s*(\\{.*?\\})\\s*\\}", Pattern.DOTALL);

    /** Llamadas a herramientas que el modelo escribio como texto en lugar de usar tool_calls. */
    List<Map.Entry<String, Map<String, Object>>> llamadasEscritas(String contenido) {
        List<Map.Entry<String, Map<String, Object>>> r = new ArrayList<>();
        for (Pattern patron : List.of(LLAMADA_JSON, LLAMADA_ESCRITA)) {
            Matcher m = patron.matcher(contenido);
            while (m.find()) {
                if (herramientas.buscar(m.group(1)).isEmpty()) continue;
                try {
                    Map<String, Object> args = json.readValue(m.group(2), new TypeReference<Map<String, Object>>() { });
                    r.add(Map.entry(m.group(1), args));
                } catch (IOException e) {
                    // no era JSON valido: se deja como texto
                }
            }
            if (!r.isEmpty()) return r;
        }
        return r;
    }

    private static final Pattern MULETILLA = Pattern.compile(
            "(por favor,? )?confirma(s)? (para (continuar|finalizar)|si (necesitas|deseas|quieres)[^.?!]*)|confirma si deseas continuar");

    /** Quita frases de relleno como "Confirma para continuar" cuando no hay nada que confirmar. */
    static String sinMuletillas(String texto) {
        List<String> frases = new ArrayList<>();
        for (String f : texto.split("(?<=[.!?])\\s+")) {
            if (!MULETILLA.matcher(Texto.normalizar(f)).find()) frases.add(f);
        }
        String r = String.join(" ", frases).trim();
        return r.isEmpty() ? texto : r;
    }

    private static final Pattern AFIRMA_ACCION = Pattern.compile("\\b(propus(e|imos)|he(mos)? propuesto|te propongo"
            + "|registr(e|amos)|vend(i|imos)|anul(e|amos)|actualic(e|amos)|emit(i|imos)|cre(e|amos) el"
            + "|se ha(n)? (registrado|emitido|vendido|descontado|anulado|actualizado|creado)"
            + "|ha sido (registrad|emitid|vendid|anulad|actualizad|cread)[oa]"
            + "|lo confirmo|confirmas? (la|el|este|esta) (venta|entrada|salida|transferencia|ajuste|anulacion|registro|cambio|movimiento))\\b");

    /** Texto que dice haber propuesto o hecho un cambio (sin tildes: "registré" -> "registre"). */
    static boolean afirmaAccion(String texto) {
        return AFIRMA_ACCION.matcher(Texto.normalizar(texto)).find();
    }

    /** Ejecuta una consulta o registra una propuesta; devuelve el texto que se le entrega al modelo. */
    private String usarHerramienta(String nombre, Map<String, Object> args, Contexto ctx, List<Accion> propuestas) {
        log.info("IA herramienta {} {}", nombre, args);
        Optional<Herramienta> h = herramientas.buscar(nombre);
        if (h.isEmpty()) return "ERROR: no existe la herramienta " + nombre;
        if (h.get().soloAdmin() && !ctx.esAdmin()) return "ERROR: solo un administrador puede usar " + nombre + ". Explícaselo al usuario.";
        try {
            if (h.get().escritura()) {
                Accion a = proponer(h.get(), args, ctx);
                propuestas.add(a);
                return "PROPUESTA_PENDIENTE: " + a.descripcion() + ". NO esta hecho todavia: el usuario debe confirmar con el boton. "
                        + "Dile en una frase que propusiste y pide confirmacion.";
            }
            Resultado r = h.get().ejecutar().apply(new Args(args), ctx);
            String datos = r.datos() == null ? "" : json.writeValueAsString(r.datos());
            if (datos.length() > 6000) datos = datos.substring(0, 6000) + "...";
            return r.texto() + (datos.isEmpty() ? "" : "\nDATOS: " + datos);
        } catch (NegocioException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            log.warn("Herramienta {} fallo", nombre, e);
            return "ERROR: no se pudo completar " + nombre + " (" + e.getClass().getSimpleName() + ")";
        }
    }

    private String instrucciones(String pregunta, Contexto ctx, Set<String> pistas) {
        StringBuilder s = new StringBuilder("""
                Eres el asistente de inteligencia artificial de SmartStock IoT, el sistema de inventario con sensores IoT \
                (ESP32, temperatura, humedad y balanza) y punto de venta de un almacen en Peru. Funcionas sin internet, en esta PC.
                Responde siempre en espanol, claro y breve (2 a 4 oraciones): tus respuestas tambien se leen en voz alta.
                Reglas:
                1. Para cualquier dato del negocio usa las herramientas. Nunca inventes cifras, productos, ids ni resultados.
                2. Para cambiar datos (movimientos, ventas, productos, alertas, ventilador, discrepancias) llama a la herramienta: \
                el sistema le mostrara al usuario un boton para confirmar. No digas que ya esta hecho; di que lo propusiste. \
                Nunca digas que propusiste algo si no llamaste a la herramienta, y nunca escribas precios ni totales \
                que no vengan de una herramienta.
                3. Si falta un dato imprescindible (producto, cantidad), preguntalo en lugar de suponerlo.
                4. Si el usuario te ensena algo, te corrige o pide que recuerdes algo, usa la herramienta recordar.
                5. Montos en soles (S/). Fechas en formato peruano.
                6. Puedes abrir pantallas, descargar reportes e imprimir. Si el usuario no es ADMIN no ofrezcas acciones de administrador.
                7. Si la pregunta tiene varias partes, llama a una herramienta por cada parte y responde todas.
                8. Pide confirmacion SOLO cuando propusiste un cambio con una herramienta. En consultas responde \
                directamente con los datos, sin pedir que confirmen nada.
                """);
        if (!pistas.isEmpty()) {
            s.append("Herramientas recomendadas para esta pregunta: ").append(String.join(", ", pistas)).append(".\n");
        }
        s.append("Usuario: ").append(ctx.usuario()).append(" (rol ").append(ctx.esAdmin() ? "ADMIN" : "OPERADOR").append("). ");
        s.append("Fecha y hora: ").append(OffsetDateTime.now(Herramientas.ZONA)
                .format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM yyyy, HH:mm", new Locale("es", "PE")))).append(".\n");
        List<MemoriaIa.Recuerdo> recuerdos = memoria.relevantes(pregunta, 12);
        if (!recuerdos.isEmpty()) {
            s.append("Lo que aprendiste de los usuarios (usalo si sirve; id entre corchetes):\n");
            recuerdos.forEach(r -> s.append("- [").append(r.id()).append("] ")
                    .append(r.tipo() == MemoriaIa.Tipo.PREFERENCIA ? "(preferencia) " : "").append(r.texto()).append('\n'));
        }
        try {
            s.append("Estado actual: ").append(diagnostico.diagnosticar().resumen());
        } catch (RuntimeException e) {
            // el diagnostico es informativo: si falla, el asistente sigue funcionando
        }
        return s.toString();
    }

    private Map<String, Object> argumentos(JsonNode nodo) {
        try {
            JsonNode n = nodo.isTextual() ? json.readTree(nodo.asText()) : nodo;
            Map<String, Object> m = json.convertValue(n, new TypeReference<Map<String, Object>>() { });
            return m == null ? Map.of() : m;
        } catch (IOException | IllegalArgumentException e) {
            return Map.of();
        }
    }

    // =====================================================================================  MOTOR PROPIO

    private Respuesta conMotorPropio(String texto, Authentication auth) {
        Matcher recordar = RECORDAR.matcher(texto.trim());
        if (recordar.matches()) return resolverIntencion("recordar", recordar.group(3), auth);

        List<ClasificadorIntenciones.Prediccion> p = clasificador.clasificar(texto);
        // Una pregunta sobre algo que le ensenaron se responde con lo aprendido
        Optional<MemoriaIa.Recuerdo> dato = memoria.respuestaDirecta(texto);
        if (dato.isPresent() && (p.isEmpty() || p.get(0).confianza() < 0.9)) {
            return simple("Según lo que me enseñaron: " + dato.get().texto() + ".", "PROPIO");
        }
        // "recordar" solo con una orden explicita («recuerda que…»): una pregunta nunca se guarda como recuerdo
        boolean modifica = !p.isEmpty() && herramientas.buscar(p.get(0).intencion()).map(Herramienta::escritura).orElse(false);
        if (!seguro(p) || p.get(0).intencion().equals("recordar") || (modifica && p.get(0).confianza() < CONFIANZA_OPERACION)) {
            // Quiza lo sabe por algo que le ensenaron
            List<MemoriaIa.Recuerdo> sabe = memoria.relevantes(texto, 3).stream()
                    .filter(r -> r.tipo() == MemoriaIa.Tipo.HECHO).toList();
            if (!sabe.isEmpty()) {
                return simple("Según lo que me enseñaron: " + sabe.stream().map(MemoriaIa.Recuerdo::texto)
                        .collect(Collectors.joining(" ")), "PROPIO");
            }
            List<String> candidatas = p.isEmpty()
                    ? List.of("diagnostico", "consultar_existencias", "resumen_ventas", "registrar_movimiento", "ayuda")
                    : p.stream().map(ClasificadorIntenciones.Prediccion::intencion).toList();
            return new Respuesta("No estoy seguro de qué necesitas. Elige una opción y aprenderé esa frase para la próxima vez.",
                    "PROPIO", List.of(), null, null, false, candidatas.stream()
                    .map(i -> new Opcion(i, ClasificadorIntenciones.ETIQUETAS.get(i))).toList());
        }
        return resolverIntencion(p.get(0).intencion(), texto, auth);
    }

    /** Seguro si la mas probable supera el minimo o le saca amplia ventaja a la segunda. */
    static boolean seguro(List<ClasificadorIntenciones.Prediccion> p) {
        if (p.isEmpty()) return false;
        double primera = p.get(0).confianza();
        double segunda = p.size() > 1 ? p.get(1).confianza() : 0;
        return primera >= CONFIANZA_MINIMA || primera >= VENTAJA_MINIMA * segunda;
    }

    private Respuesta resolverIntencion(String intencion, String texto, Authentication auth) {
        Contexto ctx = new Contexto(auth);
        try {
            if (FRASE_DIAGNOSTICO.containsKey(intencion) || intencion.equals("controlar_ventilador")) {
                String frase = intencion.equals("controlar_ventilador") ? texto + " ventilador" : FRASE_DIAGNOSTICO.get(intencion);
                return desdeDiagnostico(diagnostico.responder(frase, auth), auth);
            }
            String t = Texto.normalizar(texto);
            Map<String, Object> a = new HashMap<>();
            switch (intencion) {
                case "recordar" -> {
                    String contenido = texto.replaceFirst("(?i)^(recuerda|aprende|anota|apunta|memoriza|no olvides)( que)?[:,]?\\s*", "");
                    boolean preferencia = t.matches(".*\\b(respondeme|hablame|prefiero|quiero que|siempre|nunca)\\b.*");
                    a.put("texto", contenido);
                    a.put("tipo", preferencia ? "PREFERENCIA" : "HECHO");
                }
                case "que_recuerdas" -> {
                    List<MemoriaIa.Recuerdo> todos = memoria.todos();
                    return simple(todos.isEmpty() ? "Todavía no me has enseñado nada. Dime «recuerda que…» y lo aprenderé."
                            : "Recuerdo esto: " + todos.stream().limit(10).map(r -> "[" + r.id() + "] " + r.texto())
                            .collect(Collectors.joining("; ")) + ".", "PROPIO");
                }
                case "ayuda" -> {
                    return simple(ayuda(), "PROPIO");
                }
                case "consultar_existencias" -> {
                    herramientas.productoEnFrase(texto).ifPresent(x -> a.put("producto", x.codigo()));
                    if (t.matches(".*\\b(bajo|baj|minimo|agot|falta|faltan|reponer)\\w*.*")) a.put("solo_bajo_minimo", true);
                }
                case "buscar_productos" -> a.put("texto", herramientas.productoEnFrase(texto).map(ProductoResponse::codigo).orElse(""));
                case "pronosticar_agotamiento", "consultar_movimientos" ->
                        herramientas.productoEnFrase(texto).ifPresent(x -> a.put("producto", x.codigo()));
                case "abrir_pantalla" -> a.put("pantalla", pantallaEnFrase(t));
                case "descargar_reporte" -> a.put("tipo", t.contains("venta") ? "ventas" : t.contains("movim") ? "movimientos"
                        : t.contains("alerta") ? "alertas" : t.contains("discrep") ? "discrepancias" : "existencias");
                case "registrar_movimiento" -> movimientoEnFrase(texto, t, a);
                case "registrar_venta" -> ventaEnFrase(texto, t, a);
                default -> { /* consultas sin argumentos */ }
            }
            Herramienta h = herramientas.buscar(intencion)
                    .orElseThrow(() -> NegocioException.invalido("Todavía no sé hacer eso"));
            if (h.soloAdmin() && !ctx.esAdmin()) return simple("Eso solo puede hacerlo un administrador.", "PROPIO");
            if (h.escritura()) {
                Accion propuesta = proponer(h, a, ctx);
                return new Respuesta("Te propongo: " + propuesta.descripcion() + ". ¿Lo confirmo?", "PROPIO",
                        List.of(propuesta), null, null, false, List.of());
            }
            Resultado r = h.ejecutar().apply(new Args(a), ctx);
            return new Respuesta(r.texto(), "PROPIO", List.of(), ctx.navegar, ctx.descargar, ctx.imprimir, List.of());
        } catch (NegocioException e) {
            return simple(e.getMessage() + ".", "PROPIO");
        }
    }

    private void movimientoEnFrase(String texto, String t, Map<String, Object> a) {
        String tipo = t.matches(".*\\b(transfier|transfer|mueve|mover|pasa|traslad)\\w*.*") ? "TRANSFERENCIA"
                : t.matches(".*\\b(salida|saca|sacar|retir|baja|despach|merma|perdi)\\w*.*") ? "SALIDA" : "ENTRADA";
        a.put("tipo", tipo);
        a.put("producto", herramientas.productoEnFrase(texto).map(ProductoResponse::codigo)
                .orElseThrow(() -> NegocioException.invalido("No reconocí el producto. Dime su código o nombre")));
        a.put("cantidad", cantidadEnFrase(t).orElseThrow(() -> NegocioException.invalido("Dime la cantidad")));
        List<String> ubicaciones = ubicacionesEnFrase(t);
        if (tipo.equals("TRANSFERENCIA") && ubicaciones.size() >= 2) {
            a.put("ubicacion_origen", ubicaciones.get(0));
            a.put("ubicacion_destino", ubicaciones.get(1));
        } else if (!ubicaciones.isEmpty()) {
            a.put(tipo.equals("SALIDA") ? "ubicacion_origen" : "ubicacion_destino", ubicaciones.get(0));
        }
        a.put("motivo", "Asistente IA: " + texto);
    }

    private void ventaEnFrase(String texto, String t, Map<String, Object> a) {
        String producto = herramientas.productoEnFrase(texto).map(ProductoResponse::codigo)
                .orElseThrow(() -> NegocioException.invalido("¿Qué producto vendo? Dime su código o nombre"));
        a.put("productos", List.of(Map.of("producto", producto, "cantidad", cantidadEnFrase(t).orElse(1))));
        for (String pago : List.of("tarjeta", "yape", "plin", "transferencia")) {
            if (t.contains(pago)) a.put("metodo_pago", pago.toUpperCase());
        }
        if (t.contains("factura")) a.put("comprobante", "FACTURA");
        // "me dieron 20 soles", "pago con 50", "recibi 100": efectivo recibido para el vuelto
        Matcher recibido = RECIBIDO.matcher(t);
        if (recibido.find()) a.put("monto_recibido", recibido.group(3).replace(',', '.'));
        else if (t.contains("nota")) a.put("comprobante", "NOTA_VENTA");
        herramientas.ubicacionEnFrase(texto).ifPresent(u -> a.put("ubicacion", u.nombre()));
    }

    /** Primer numero de la frase que no sea parte de un codigo (P001) ni de un nombre de ubicacion. */
    private static Optional<Integer> cantidadEnFrase(String t) {
        Matcher m = NUMERO.matcher(t);
        while (m.find()) {
            int inicio = m.start();
            if (inicio > 0 && Character.isLetter(t.charAt(inicio - 1))) continue;
            return Optional.of(Integer.parseInt(m.group(1)));
        }
        Map<String, Integer> palabras = Map.ofEntries(Map.entry("un", 1), Map.entry("una", 1), Map.entry("dos", 2),
                Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5), Map.entry("seis", 6),
                Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10),
                Map.entry("veinte", 20), Map.entry("treinta", 30), Map.entry("cincuenta", 50), Map.entry("cien", 100));
        for (String w : t.split(" ")) if (palabras.containsKey(w)) return Optional.of(palabras.get(w));
        return Optional.empty();
    }

    private List<String> ubicacionesEnFrase(String t) {
        return herramientas.ubicacionesActivas().stream().filter(u -> t.contains(Texto.normalizar(u.nombre())))
                .sorted(Comparator.comparingInt(u -> t.indexOf(Texto.normalizar(u.nombre()))))
                .map(UbicacionResponse::nombre).toList();
    }

    private static String pantallaEnFrase(String t) {
        if (t.matches(".*\\b(venta|vender|caja|cobr|boleta|punto de venta)\\w*.*")) return "ventas";
        if (t.matches(".*\\b(inventario|stock|movimiento|existencia)\\w*.*")) return "inventario";
        if (t.matches(".*\\b(producto|catalogo)\\w*.*")) return "productos";
        if (t.matches(".*\\b(dispositivo|iot|esp32|sensor|calibra)\\w*.*")) return "dispositivos";
        if (t.matches(".*\\b(alerta|reporte|auditoria)\\w*.*")) return "alertas";
        if (t.matches(".*\\b(asistente|ia|inteligencia)\\b.*")) return "asistente";
        return "dashboard";
    }

    private Respuesta desdeDiagnostico(RespuestaAsistente r, Authentication auth) {
        if (r.porConfirmar().isEmpty()) return simple(r.texto(), "PROPIO");
        Map<String, Object> args = Map.of("codigos", r.porConfirmar().stream().map(x -> x.codigo()).toList());
        String descripcion = r.porConfirmar().stream().map(x -> x.descripcion()).collect(Collectors.joining("; "));
        Pendiente p = guardar(auth.getName(), ACCIONES_DIAGNOSTICO, args, descripcion);
        return new Respuesta(r.texto(), "PROPIO", List.of(new Accion(p.id(), p.descripcion())), null, null, false, List.of());
    }

    // =====================================================================================  PROPUESTAS

    private Accion proponer(Herramienta h, Map<String, Object> args, Contexto ctx) {
        if (h.soloAdmin() && !ctx.esAdmin()) throw NegocioException.invalido("Solo un administrador puede hacerlo");
        String descripcion = h.describir().apply(new Args(args), ctx);   // valida los datos antes de proponer
        Pendiente p = guardar(ctx.usuario(), h.nombre(), args, descripcion);
        return new Accion(p.id(), p.descripcion());
    }

    private Pendiente guardar(String usuario, String herramienta, Map<String, Object> args, String descripcion) {
        OffsetDateTime limite = OffsetDateTime.now().minus(VIGENCIA_PROPUESTA);
        pendientes.values().removeIf(x -> x.creado().isBefore(limite));
        Pendiente p = new Pendiente(UUID.randomUUID().toString(), usuario, herramienta, new HashMap<>(args),
                descripcion, OffsetDateTime.now());
        pendientes.put(p.id(), p);
        return p;
    }

    private List<Pendiente> pendientesDe(String usuario) {
        OffsetDateTime limite = OffsetDateTime.now().minus(VIGENCIA_PROPUESTA);
        pendientes.values().removeIf(x -> x.creado().isBefore(limite));
        return pendientes.values().stream().filter(p -> p.usuario().equals(usuario))
                .sorted(Comparator.comparing(Pendiente::creado)).toList();
    }

    private Respuesta confirmarTodas(List<Pendiente> lista, Authentication auth) {
        Contexto ctx = new Contexto(auth);
        List<String> hechos = new ArrayList<>();
        for (Pendiente p : lista) {
            if (pendientes.remove(p.id()) == null) continue;   // otra pestana ya la confirmo
            try {
                if (p.herramienta().equals(ACCIONES_DIAGNOSTICO)) {
                    @SuppressWarnings("unchecked")
                    List<String> codigos = (List<String>) p.args().get("codigos");
                    hechos.add(diagnostico.ejecutar(codigos, auth).texto());
                    continue;
                }
                Herramienta h = herramientas.buscar(p.herramienta()).orElseThrow();
                if (h.soloAdmin() && !ctx.esAdmin()) throw NegocioException.invalido("solo un administrador puede hacerlo");
                hechos.add(h.ejecutar().apply(new Args(p.args()), ctx).texto());
            } catch (NegocioException e) {
                hechos.add("No se pudo completar (" + p.descripcion() + "): " + e.getMessage() + ".");
            } catch (RuntimeException e) {
                log.warn("Fallo la accion confirmada {}", p.herramienta(), e);
                hechos.add("No se pudo completar (" + p.descripcion() + ").");
            }
        }
        String texto = hechos.isEmpty() ? "No había nada pendiente." : String.join(" ", hechos);
        recordarTurno(auth.getName(), "(confirmó la acción propuesta)", texto);
        return new Respuesta(texto, motorActual(), List.of(), ctx.navegar, ctx.descargar, ctx.imprimir, List.of());
    }

    // =====================================================================================  HISTORIAL

    private void anotar(Authentication auth, String rol, String texto) {
        if (texto == null || texto.isBlank()) return;
        Deque<Mensaje> h = historial.computeIfAbsent(auth.getName(), k -> new ArrayDeque<>());
        synchronized (h) {
            h.addLast(new Mensaje(rol, texto, OffsetDateTime.now()));
            while (h.size() > MAX_HISTORIAL) h.removeFirst();
        }
    }

    private List<Map<String, Object>> contextoDe(String usuario) {
        Deque<Map<String, Object>> c = contexto.get(usuario);
        if (c == null) return List.of();
        synchronized (c) {
            return List.copyOf(c);
        }
    }

    private void recordarTurno(String usuario, String pregunta, String respuesta) {
        Deque<Map<String, Object>> c = contexto.computeIfAbsent(usuario, k -> new ArrayDeque<>());
        synchronized (c) {
            c.addLast(Map.of("role", "user", "content", pregunta));
            c.addLast(Map.of("role", "assistant", "content", respuesta));
            while (c.size() > MAX_CONTEXTO) c.removeFirst();
        }
    }

    // =====================================================================================  UTILIDADES

    private String motorActual() {
        return modelo.disponible() ? "MODELO" : "PROPIO";
    }

    private static Respuesta simple(String texto, String motor) {
        return new Respuesta(texto, motor, List.of(), null, null, false, List.of());
    }

    private static String minuscula(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String ayuda() {
        return "Tengo acceso a todo el programa. Puedo consultar stock, productos, ventas, movimientos, alertas, "
                + "dispositivos y la temperatura; predecir cuándo se agota cada producto y cuánto reponer; registrar entradas, "
                + "salidas, transferencias y ventas; crear o editar productos; controlar el ventilador; abrir pantallas, "
                + "descargar reportes e imprimir. Lo que cambia datos te lo propongo y lo hago cuando confirmas. "
                + "Aprendo: dime «recuerda que…» y lo guardo, y si no te entiendo, elige la opción correcta y aprenderé tu frase.";
    }
}
