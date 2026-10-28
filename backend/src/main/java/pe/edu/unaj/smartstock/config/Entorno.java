package pe.edu.unaj.smartstock.config;

import java.nio.file.Path;

/**
 * Ubicacion de la configuracion local del equipo, comun al servidor y a la aplicacion de escritorio.
 * Por defecto es %USERPROFILE%\.smartstock; la variable SMARTSTOCK_HOME permite cambiarla.
 * Se usa antes de arrancar Spring, por eso no es un bean.
 */
public final class Entorno {

    /** Carpeta con application.properties (configuracion del equipo) y smartstock.log. */
    public static final Path CARPETA = carpeta();

    /** Archivo que escribe el asistente de instalacion y que Spring lee al iniciar. */
    public static final Path CONFIGURACION = CARPETA.resolve("application.properties");

    private Entorno() { }

    private static Path carpeta() {
        String propia = System.getenv("SMARTSTOCK_HOME");
        if (propia != null && !propia.isBlank()) return Path.of(propia);
        return Path.of(System.getProperty("user.home"), ".smartstock");
    }

    /** Hace que Spring lea, si existe, el application.properties de la carpeta del equipo. */
    public static void registrarConfiguracionLocal() {
        if (System.getProperty("spring.config.additional-location") == null) {
            System.setProperty("spring.config.additional-location", "optional:file:" + CARPETA.toUri().getPath());
        }
    }
}
