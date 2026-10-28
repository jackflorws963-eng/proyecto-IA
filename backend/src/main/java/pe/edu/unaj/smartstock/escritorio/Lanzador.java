package pe.edu.unaj.smartstock.escritorio;

import javafx.application.Application;
import pe.edu.unaj.smartstock.SmartStockApplication;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;

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
