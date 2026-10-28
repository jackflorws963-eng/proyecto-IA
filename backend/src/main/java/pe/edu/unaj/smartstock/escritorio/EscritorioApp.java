package pe.edu.unaj.smartstock.escritorio;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import netscape.javascript.JSObject;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.context.ConfigurableApplicationContext;
import pe.edu.unaj.smartstock.SmartStockApplication;
import pe.edu.unaj.smartstock.config.Entorno;
import pe.edu.unaj.smartstock.instalacion.ConfiguradorBaseDatos;
import pe.edu.unaj.smartstock.instalacion.ConfiguradorBaseDatos.Datos;
import pe.edu.unaj.smartstock.instalacion.ConfiguradorBaseDatos.Resultado;

import java.net.ConnectException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class EscritorioApp extends Application {

    static final String TITULO = "SmartStock IoT";

    private ConfigurableApplicationContext contexto;
    private Stage presentacion;
    private Label estado;
    private PuenteEscritorio puente;

    @Override
    public void start(Stage ventana) {
        Platform.setImplicitExit(false);
        mostrarPresentacion();
        enSegundoPlano(() -> arrancar(ventana));
    }

    private void mostrarPresentacion() {
        Label titulo = new Label(TITULO);
        titulo.setStyle("-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");
        estado = new Label("Iniciando servidor y base de datos...");
        estado.setStyle("-fx-text-fill: #475569;");
        Label autor = new Label("Desarrollado por BR");
        autor.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 11px;");
        VBox caja = new VBox(16, titulo, new ProgressIndicator(), estado, autor);
        caja.setAlignment(Pos.CENTER);
        caja.setPadding(new Insets(40));
        caja.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1;");
        presentacion = new Stage(StageStyle.UNDECORATED);
        presentacion.getIcons().add(icono());
        presentacion.setScene(new Scene(caja, 380, 250));
        presentacion.show();
    }

    private void arrancar(Stage ventana) {
        Entorno.registrarConfiguracionLocal();
        if (System.getProperty("logging.file.name") == null) {
            System.setProperty("logging.file.name", Entorno.CARPETA.resolve("smartstock.log").toString());
        }
        if (ConfiguradorBaseDatos.faltaConfiguracion(Entorno.CONFIGURACION) && !configurarEquipo(null)) {
            Platform.runLater(Platform::exit);
            return;
        }
        enFx(() -> estado.setText("Iniciando servidor y base de datos..."));
        try {
            SpringApplication app = new SpringApplicationBuilder(SmartStockApplication.class)
                    .headless(false)
                    .build();
            contexto = app.run(getParameters().getRaw().toArray(String[]::new));
            int puerto = ((WebServerApplicationContext) contexto).getWebServer().getPort();
            Platform.runLater(() -> abrirVentana(ventana, URI.create("http://localhost:" + puerto + "/")));
        } catch (Throwable e) {
            fallo(ventana, e);
        }
    }

    private void fallo(Stage ventana, Throwable e) {
        String explicacion = explicar(e);
        boolean deBaseDatos = esErrorBaseDatos(e);
        Platform.runLater(() -> {
            presentacion.hide();
            if (!deBaseDatos) {
                error("No se pudo iniciar " + TITULO, explicacion);
                Platform.exit();
                return;
            }
            ButtonType reconfigurar = new ButtonType("Configurar de nuevo", ButtonBar.ButtonData.OK_DONE);
            ButtonType salir = new ButtonType("Salir", ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert a = new Alert(Alert.AlertType.ERROR, explicacion, reconfigurar, salir);
            a.setTitle(TITULO);
            a.setHeaderText("No se pudo conectar con la base de datos");
            if (a.showAndWait().orElse(salir) != reconfigurar) {
                Platform.exit();
                return;
            }
            presentacion.show();
            enSegundoPlano(() -> {
                if (configurarEquipo(null)) arrancar(ventana);
                else Platform.runLater(Platform::exit);
            });
        });
    }

    private boolean configurarEquipo(String errorInicial) {
        Datos datos = null;
        String error = errorInicial;
        while (true) {
            Datos previos = datos;
            String mensajeError = error;
            Optional<Datos> respuesta = enFx(() -> {
                presentacion.hide();
                return AsistenteInstalacion.pedirDatos(previos, mensajeError);
            });
            if (respuesta.isEmpty()) return false;
            datos = respuesta.get();
            error = datos.validar();
            if (error != null) continue;

            enFx(() -> {
                estado.setText("Configurando la base de datos...");
                presentacion.show();
            });
            try {
                Resultado r = new ConfiguradorBaseDatos(Entorno.CONFIGURACION).configurar(datos);
                enFx(() -> mostrarResumen(r));
                return true;
            } catch (Exception e) {
                error = "No se pudo configurar: " + raiz(e).getMessage();
            }
        }
    }

    private void mostrarResumen(Resultado r) {
        TextArea texto = new TextArea("""
                Configuracion guardada en:
                %s

                Usuarios: admin y operador (con las contrasenas que eligio).%s

                Clave del dispositivo ESP32-01 (copiela en firmware/smartstock_esp32/secrets.h como DEVICE_KEY):
                %s

                Si el ESP32-01 ya tenia una clave propia, se conserva; puede generar otra desde
                Dispositivos IoT > Nueva clave."""
                .formatted(r.archivo(), r.baseExistente()
                        ? "\nSe reutilizo la base existente: si esos usuarios ya tenian una contrasena propia, se conserva."
                        : "", r.claveDispositivo()));
        texto.setEditable(false);
        texto.setWrapText(true);
        texto.setPrefSize(520, 230);
        Alert a = new Alert(Alert.AlertType.INFORMATION, null, ButtonType.OK);
        a.setTitle(TITULO);
        a.setHeaderText("SmartStock IoT quedo configurado");
        a.getDialogPane().setContent(texto);
        a.showAndWait();
    }

    private void abrirVentana(Stage ventana, URI base) {
        WebView web = new WebView();
        WebEngine motor = web.getEngine();
        puente = new PuenteEscritorio(ventana, base, motor);

        motor.setOnAlert(e -> mensaje(Alert.AlertType.INFORMATION, e.getData()));
        motor.setConfirmHandler(texto -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION, texto, ButtonType.OK, ButtonType.CANCEL);
            a.setHeaderText(null);
            a.initOwner(ventana);
            return a.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        });
        motor.setPromptHandler(p -> {
            TextInputDialog d = new TextInputDialog(p.getDefaultValue());
            d.setTitle(TITULO);
            d.setHeaderText(p.getMessage());
            d.initOwner(ventana);
            return d.showAndWait().orElse(null);
        });
        motor.getLoadWorker().stateProperty().addListener((obs, antes, ahora) -> {
            if (ahora == Worker.State.SUCCEEDED) {
                ((JSObject) motor.executeScript("window")).setMember("escritorio", puente);
            }
        });
        motor.titleProperty().addListener((obs, antes, ahora) ->
                ventana.setTitle(ahora == null ? TITULO : ahora));

        Rectangle2D area = Screen.getPrimary().getVisualBounds();
        Scene escena = new Scene(web, area.getWidth(), area.getHeight());
        escena.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F5) motor.reload();
            else if (e.getCode() == KeyCode.F11) ventana.setFullScreen(!ventana.isFullScreen());
        });
        ventana.getIcons().add(icono());
        ventana.setTitle(TITULO);
        ventana.setScene(escena);
        ventana.setOnCloseRequest(e -> Platform.exit());
        motor.load(base.resolve("/login.html").toString());
        ventana.show();
        ventana.setMaximized(true);
        presentacion.close();
    }

    @Override
    public void stop() {
        if (puente != null) puente.cerrar();
        if (contexto != null) SpringApplication.exit(contexto);
        System.exit(0);
    }

    static Image icono() {
        return new Image(EscritorioApp.class.getResource("/static/img/logo.png").toExternalForm());
    }

    private static void enSegundoPlano(Runnable tarea) {
        Thread hilo = new Thread(tarea, "arranque-spring");
        hilo.setContextClassLoader(EscritorioApp.class.getClassLoader());
        hilo.setDaemon(true);
        hilo.start();
    }

    private static <T> T enFx(Supplier<T> accion) {
        CompletableFuture<T> futuro = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                futuro.complete(accion.get());
            } catch (Throwable t) {
                futuro.completeExceptionally(t);
            }
        });
        return futuro.join();
    }

    private static void enFx(Runnable accion) {
        enFx(() -> {
            accion.run();
            return null;
        });
    }

    private static Throwable raiz(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) {
            if (r instanceof PortInUseException) break;
            r = r.getCause();
        }
        return r;
    }

    private static boolean esErrorBaseDatos(Throwable e) {
        Throwable r = raiz(e);
        String detalle = String.valueOf(r.getMessage());
        return r instanceof ConnectException || r instanceof java.sql.SQLException
                || detalle.contains("Connection to") || detalle.contains("password authentication")
                || detalle.contains("does not exist");
    }

    private static String explicar(Throwable e) {
        Throwable r = raiz(e);
        if (r instanceof PortInUseException p) {
            return "El puerto " + p.getPort() + " ya esta en uso. Cierre la otra instancia de SmartStock "
                    + "o cambie server.port en " + Entorno.CONFIGURACION + ".";
        }
        String detalle = r.getClass().getSimpleName() + ": " + r.getMessage();
        if (esErrorBaseDatos(e)) {
            return "Verifique que PostgreSQL este instalado e iniciado y que los datos de conexion sean "
                    + "correctos (" + Entorno.CONFIGURACION + ").\n\nDetalle: " + detalle;
        }
        return detalle + "\n\nRevise el registro: " + Entorno.CARPETA.resolve("smartstock.log");
    }

    static void mensaje(Alert.AlertType tipo, String texto) {
        Alert a = new Alert(tipo, texto, ButtonType.OK);
        a.setTitle(TITULO);
        a.setHeaderText(null);
        a.showAndWait();
    }

    static void error(String titulo, String texto) {
        Alert a = new Alert(Alert.AlertType.ERROR, texto, ButtonType.OK);
        a.setTitle(TITULO);
        a.setHeaderText(titulo);
        a.showAndWait();
    }
}
