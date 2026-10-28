package pe.edu.unaj.smartstock.config;

import java.nio.file.Path;

public final class Entorno {

    public static final Path CARPETA = carpeta();

    public static final Path CONFIGURACION = CARPETA.resolve("application.properties");

    private Entorno() { }

    private static Path carpeta() {
        String propia = System.getenv("SMARTSTOCK_HOME");
        if (propia != null && !propia.isBlank()) return Path.of(propia);
        return Path.of(System.getProperty("user.home"), ".smartstock");
    }

    public static void registrarConfiguracionLocal() {
        if (System.getProperty("spring.config.additional-location") == null) {
            System.setProperty("spring.config.additional-location", "optional:file:" + CARPETA.toUri().getPath());
        }
    }
}
