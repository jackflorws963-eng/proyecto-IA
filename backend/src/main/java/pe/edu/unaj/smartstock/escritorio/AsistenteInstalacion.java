package pe.edu.unaj.smartstock.escritorio;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import pe.edu.unaj.smartstock.instalacion.ConfiguradorBaseDatos.Datos;

import java.util.Optional;

/**
 * Ventana del primer arranque: pide la conexion a PostgreSQL y las contrasenas iniciales.
 * El trabajo con la base lo hace {@link pe.edu.unaj.smartstock.instalacion.ConfiguradorBaseDatos}.
 */
final class AsistenteInstalacion {

    private AsistenteInstalacion() { }

    /** Muestra el formulario (hilo de JavaFX). Vacio si la persona cancela. */
    static Optional<Datos> pedirDatos(Datos anteriores, String error) {
        Dialog<ButtonType> dialogo = new Dialog<>();
        dialogo.setTitle("SmartStock IoT - Configuracion inicial");
        dialogo.setHeaderText("Bienvenido a SmartStock IoT\nConfiguremos la base de datos de este equipo");
        ButtonType configurar = new ButtonType("Configurar", ButtonBar.ButtonData.OK_DONE);
        dialogo.getDialogPane().getButtonTypes().addAll(configurar, ButtonType.CANCEL);

        TextField host = new TextField(anteriores == null ? "localhost" : anteriores.host());
        TextField puerto = new TextField(anteriores == null ? "5432" : String.valueOf(anteriores.puerto()));
        TextField superusuario = new TextField(anteriores == null ? "postgres" : anteriores.superusuario());
        PasswordField claveSuper = new PasswordField();
        TextField base = new TextField(anteriores == null ? "smartstock" : anteriores.baseDatos());
        PasswordField claveAdmin = new PasswordField();
        PasswordField claveOperador = new PasswordField();
        TextField puertoWeb = new TextField(anteriores == null ? "8080" : String.valueOf(anteriores.puertoWeb()));
        if (anteriores != null) {
            claveAdmin.setText(anteriores.claveAdmin());
            claveOperador.setText(anteriores.claveOperador());
        }

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        int f = 0;
        g.addRow(f++, seccion("PostgreSQL (debe estar instalado e iniciado)"));
        g.addRow(f++, new Label("Servidor"), host);
        g.addRow(f++, new Label("Puerto"), puerto);
        g.addRow(f++, new Label("Usuario administrador"), superusuario);
        g.addRow(f++, new Label("Contrasena del administrador"), claveSuper);
        g.addRow(f++, new Label("Nombre de la base"), base);
        g.addRow(f++, seccion("Usuarios de SmartStock (minimo 8 caracteres)"));
        g.addRow(f++, new Label("Contrasena de 'admin'"), claveAdmin);
        g.addRow(f++, new Label("Contrasena de 'operador'"), claveOperador);
        g.addRow(f++, seccion("Red"));
        g.addRow(f++, new Label("Puerto web (ESP32 y navegador)"), puertoWeb);

        Label nota = new Label("""
                La contrasena del administrador de PostgreSQL se usa una sola vez y no se guarda.
                Se creara el usuario de base de datos "smartstock" con una clave aleatoria.
                Si ya tenia una base "smartstock", se conservan sus datos.""");
        nota.setStyle("-fx-text-fill: #475569;");
        VBox contenido = new VBox(12, g, nota);
        if (error != null) {
            Label e = new Label(error);
            e.setWrapText(true);
            e.setMaxWidth(460);
            e.setStyle("-fx-text-fill: #b91c1c;");
            contenido.getChildren().add(0, e);
        }
        contenido.setPadding(new Insets(10));
        dialogo.getDialogPane().setContent(contenido);
        dialogo.setOnShown(e -> claveSuper.requestFocus());

        if (dialogo.showAndWait().orElse(ButtonType.CANCEL) != configurar) return Optional.empty();
        return Optional.of(new Datos(host.getText().trim(), entero(puerto.getText()), superusuario.getText().trim(),
                claveSuper.getText(), base.getText().trim().toLowerCase(), claveAdmin.getText(),
                claveOperador.getText(), entero(puertoWeb.getText())));
    }

    private static Label seccion(String texto) {
        Label l = new Label(texto);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #0f766e; -fx-padding: 6 0 0 0;");
        GridPane.setColumnSpan(l, 2);
        return l;
    }

    private static int entero(String texto) {
        try {
            return Integer.parseInt(texto.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
