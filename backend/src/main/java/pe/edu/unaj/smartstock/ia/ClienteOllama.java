package pe.edu.unaj.smartstock.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente del modelo de lenguaje local (Ollama, https://ollama.com). Ollama ejecuta el modelo en esta
 * PC (CPU o GPU): una vez descargado el modelo no se necesita internet.
 * Para garantizarlo, solo se aceptan direcciones de este mismo equipo (localhost); cualquier otra se ignora.
 */
@Component
public class ClienteOllama {

    private static final Logger log = LoggerFactory.getLogger(ClienteOllama.class);
    private static final Duration CACHE_ESTADO = Duration.ofSeconds(20);

    private final ObjectMapper json;
    private final HttpClient http;
    private final URI base;
    private final String modelo;
    private final boolean habilitada;
    private final Duration tiempoMaximo;

    private volatile long revisadoEn;
    private volatile boolean disponible;
    private volatile String motivo = "Sin revisar";

    public ClienteOllama(ObjectMapper json,
                         @Value("${smartstock.ia.url:http://localhost:11434}") String url,
                         @Value("${smartstock.ia.modelo:qwen2.5:7b}") String modelo,
                         @Value("${smartstock.ia.habilitada:true}") boolean habilitada,
                         @Value("${smartstock.ia.tiempo-maximo-s:180}") long tiempoMaximoS) {
        this.json = json;
        this.base = URI.create(url.endsWith("/") ? url : url + "/");
        this.modelo = modelo;
        this.tiempoMaximo = Duration.ofSeconds(tiempoMaximoS);
        boolean local = esLocal(base.getHost());
        if (!local) log.warn("smartstock.ia.url={} no es de este equipo: la IA local queda desactivada (sin internet)", url);
        this.habilitada = habilitada && local;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    static boolean esLocal(String host) {
        if (host == null) return false;
        if (host.equalsIgnoreCase("localhost")) return true;
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    public String modelo() { return modelo; }

    public String motivo() {
        disponible();
        return motivo;
    }

    /** true si Ollama responde y tiene el modelo descargado (se revisa como mucho cada 20 s). */
    public boolean disponible() {
        if (!habilitada) {
            motivo = "IA local desactivada en la configuracion";
            return false;
        }
        long ahora = System.nanoTime();
        if (ahora - revisadoEn < CACHE_ESTADO.toNanos()) return disponible;
        revisadoEn = ahora;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            boolean tieneModelo = false;
            for (JsonNode m : json.readTree(r.body()).path("models")) {
                String nombre = m.path("name").asText();
                if (nombre.equals(modelo) || nombre.equals(modelo + ":latest")) tieneModelo = true;
            }
            disponible = tieneModelo;
            motivo = tieneModelo ? "Modelo " + modelo + " listo"
                    : "Ollama esta instalado pero falta el modelo: ejecute  ollama pull " + modelo;
        } catch (IOException e) {
            disponible = false;
            motivo = "Ollama no esta en ejecucion (instalelo desde ollama.com y abralo)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            disponible = false;
        }
        return disponible;
    }

    /**
     * Un turno de conversacion con herramientas. Devuelve el mensaje del modelo:
     * {"role":"assistant","content":"...","tool_calls":[{"function":{"name":..,"arguments":{..}}}]}.
     */
    public JsonNode chat(List<Map<String, Object>> mensajes, List<Map<String, Object>> herramientas) throws IOException {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("model", modelo);
        cuerpo.put("messages", mensajes);
        cuerpo.put("tools", herramientas);
        cuerpo.put("stream", false);
        cuerpo.put("keep_alive", "30m");
        cuerpo.put("options", Map.of("temperature", 0.2, "num_ctx", 8192));
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("api/chat"))
                    .timeout(tiempoMaximo)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException("Ollama respondio " + r.statusCode() + ": " + r.body());
            return json.readTree(r.body()).path("message");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido", e);
        } catch (IOException e) {
            revisadoEn = 0;   // fuerza a revisar el estado en la proxima pregunta
            throw e;
        }
    }
}
