package pe.edu.unaj.smartstock.escritorio;

import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.print.PrinterJob;
import javafx.scene.control.Alert;
import javafx.scene.web.WebEngine;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Objeto que JavaScript ve como window.escritorio.
 * El navegador integrado no descarga archivos, asi que los reportes CSV se piden desde Java
 * (con la misma cookie de sesion del usuario) y se guardan con el dialogo "Guardar como".
 * Tambien da voz al asistente: el navegador integrado no trae sintesis ni reconocimiento de voz.
 */
public class PuenteEscritorio {

    private static final Pattern NOMBRE_ARCHIVO = Pattern.compile("filename=\"?([^\";]+)\"?");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Stage ventana;
    private final URI base;
    private final WebEngine motor;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Voz voz = new Voz();

    PuenteEscritorio(Stage ventana, URI base, WebEngine motor) {
        this.ventana = ventana;
        this.base = base;
        this.motor = motor;
    }

    /** Imprime la pagina actual (ventas.js deja visible solo el ticket con @media print). */
    public void imprimir() {
        PrinterJob trabajo = PrinterJob.createPrinterJob();
        if (trabajo == null) {
            EscritorioApp.error("No se pudo imprimir", "No hay impresoras instaladas en Windows.");
            return;
        }
        if (trabajo.showPrintDialog(ventana)) {
            motor.print(trabajo);
            trabajo.endJob();
        } else {
            trabajo.cancelJob();
        }
    }

    // ---------- Voz del asistente (ver asistente.js) ----------

    public boolean vozDisponible() { return Voz.disponible(); }

    public void hablar(String texto) { voz.hablar(texto); }

    public void callar() { voz.callar(); }

    /** Avisa a la pagina con window.vozEvento(tipo, dato): tipo = escuchando | texto | error. */
    public void escuchar() {
        voz.escuchar(() -> avisarPagina("escuchando", ""),
                texto -> avisarPagina("texto", texto),
                error -> avisarPagina("error", error));
    }

    void cerrar() { voz.cerrar(); }

    private void avisarPagina(String tipo, String dato) {
        Platform.runLater(() -> {
            try {
                motor.executeScript("window.vozEvento && window.vozEvento(" + JSON.writeValueAsString(tipo) + ", "
                        + JSON.writeValueAsString(dato) + ")");
            } catch (IOException | RuntimeException ignorada) {
                // la pagina cambio mientras se escuchaba: no hay a quien avisar
            }
        });
    }

    /** Llamado desde alertas.js con una ruta relativa, p. ej. /api/reportes/existencias.csv?desde=... */
    public void descargar(String ruta) {
        URI uri = base.resolve(ruta);
        if (!uri.getPath().startsWith("/api/reportes/")) return;
        HttpRequest.Builder peticion = HttpRequest.newBuilder(uri).GET();
        try {
            CookieHandler cookies = CookieHandler.getDefault();
            if (cookies != null) {
                cookies.get(uri, Map.of()).getOrDefault("Cookie", List.of())
                        .forEach(c -> peticion.header("Cookie", c));
            }
        } catch (IOException e) {
            EscritorioApp.error("No se pudo descargar el reporte", e.getMessage());
            return;
        }
        http.sendAsync(peticion.build(), HttpResponse.BodyHandlers.ofByteArray())
                .whenComplete((resp, e) -> Platform.runLater(() -> {
                    if (e != null) EscritorioApp.error("No se pudo descargar el reporte", e.getMessage());
                    else guardar(resp);
                }));
    }

    private void guardar(HttpResponse<byte[]> resp) {
        if (resp.statusCode() != 200) {
            EscritorioApp.error("No se pudo descargar el reporte", mensajeError(resp));
            return;
        }
        FileChooser dialogo = new FileChooser();
        dialogo.setTitle("Guardar reporte");
        dialogo.setInitialFileName(resp.headers().firstValue("Content-Disposition")
                .map(NOMBRE_ARCHIVO::matcher).filter(Matcher::find).map(m -> m.group(1))
                .orElse("reporte.csv"));
        dialogo.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (Excel)", "*.csv"));
        File descargas = Path.of(System.getProperty("user.home"), "Downloads").toFile();
        if (descargas.isDirectory()) dialogo.setInitialDirectory(descargas);

        File destino = dialogo.showSaveDialog(ventana);
        if (destino == null) return;
        try {
            Files.write(destino.toPath(), resp.body());
            EscritorioApp.mensaje(Alert.AlertType.INFORMATION, "Reporte guardado en:\n" + destino);
        } catch (IOException e) {
            EscritorioApp.error("No se pudo guardar el reporte", e.getMessage());
        }
    }

    /** Los errores de la API llegan como JSON {"mensaje": "..."} (ver RespuestaJson). */
    private static String mensajeError(HttpResponse<byte[]> resp) {
        try {
            String mensaje = JSON.readTree(resp.body()).path("mensaje").asText("");
            if (!mensaje.isBlank()) return mensaje;
        } catch (IOException ignorada) {
            // cuerpo que no es JSON: se informa solo el codigo HTTP
        }
        return "Error HTTP " + resp.statusCode();
    }
}
