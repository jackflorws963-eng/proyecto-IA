package pe.edu.unaj.smartstock.escritorio;

import javafx.application.Application;
import pe.edu.unaj.smartstock.SmartStockApplication;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;

/**
 * Punto de entrada del .jar y del instalador.
 * <ul>
 *   <li>Sin argumentos: abre la aplicacion de escritorio (ventana JavaFX con el servidor integrado).</li>
 *   <li>{@code --servidor}, o un equipo sin pantalla (Docker, servidor Linux): solo el servidor web.</li>
 * </ul>
 * Es una clase aparte que NO extiende Application: asi JavaFX arranca aunque sus
 * librerias esten en el classpath (dentro del .jar de Spring Boot) y no como modulos.
 */
public final class Lanzador {

    private Lanzador() { }

    public static void main(String[] args) {
        boolean soloServidor = Arrays.stream(args).anyMatch(a -> a.equals("--servidor") || a.equals("servidor"));
        if (soloServidor || GraphicsEnvironment.isHeadless()) {
            String[] resto = Arrays.stream(args)
                    .filter(a -> !a.equals("--servidor") && !a.equals("servidor"))
                    .toArray(String[]::new);
            SmartStockApplication.main(resto);
            return;
        }
        Application.launch(EscritorioApp.class, args);
    }
}
