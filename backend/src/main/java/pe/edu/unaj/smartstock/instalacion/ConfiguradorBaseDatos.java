package pe.edu.unaj.smartstock.instalacion;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

public class ConfiguradorBaseDatos {

    public static final String USUARIO_APP = "smartstock";

    private static final Pattern IDENTIFICADOR = Pattern.compile("[a-z_][a-z0-9_]{0,62}");
    private static final String ALFABETO = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom AZAR = new SecureRandom();

    private static final String TRANSFERIR_OBJETOS = """
            DO $$
            DECLARE r record;
            BEGIN
              FOR r IN SELECT c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                       WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p', 'v', 'm', 'S')
                       ORDER BY c.relkind = 'S' LOOP
                BEGIN
                  EXECUTE format('ALTER %s public.%I OWNER TO %I',
                      CASE r.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                                     WHEN 'S' THEN 'SEQUENCE' ELSE 'TABLE' END,
                      r.relname, '{rol}');
                EXCEPTION WHEN others THEN
                  NULL; -- secuencias de columnas BIGSERIAL: ya cambiaron junto con su tabla
                END;
              END LOOP;
              FOR r IN SELECT p.oid::regprocedure AS firma FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                       WHERE n.nspname = 'public' LOOP
                EXECUTE format('ALTER ROUTINE %s OWNER TO %I', r.firma, '{rol}');
              END LOOP;
            END $$""";

    public record Datos(String host, int puerto, String superusuario, String claveSuperusuario,
                        String baseDatos, String claveAdmin, String claveOperador, int puertoWeb) {

        public String validar() {
            if (host == null || host.isBlank()) return "Indique el servidor de PostgreSQL";
            if (puerto < 1 || puerto > 65535) return "El puerto de PostgreSQL no es valido";
            if (puertoWeb < 1 || puertoWeb > 65535) return "El puerto web no es valido";
            if (superusuario == null || superusuario.isBlank()) return "Indique el usuario administrador de PostgreSQL";
            if (baseDatos == null || !IDENTIFICADOR.matcher(baseDatos).matches()) {
                return "El nombre de la base solo admite minusculas, numeros y guion bajo";
            }
            if (claveAdmin == null || claveAdmin.length() < 8) return "La contrasena de 'admin' debe tener al menos 8 caracteres";
            if (claveOperador == null || claveOperador.length() < 8) {
                return "La contrasena de 'operador' debe tener al menos 8 caracteres";
            }
            return null;
        }
    }

    public record Resultado(Path archivo, String claveDispositivo, boolean baseExistente) { }

    private final Path archivo;

    public ConfiguradorBaseDatos(Path archivo) {
        this.archivo = archivo;
    }

    public static boolean faltaConfiguracion(Path archivo) {
        if (System.getenv("DB_PASSWORD") != null) return false;
        if (!Files.isRegularFile(archivo)) return true;
        return leer(archivo).getProperty("spring.datasource.password", "").isBlank();
    }

    public Resultado configurar(Datos d) throws SQLException, IOException {
        String problema = d.validar();
        if (problema != null) throw new IllegalArgumentException(problema);

        String claveBd = aleatoria(24);
        String claveDispositivo = aleatoria(32);
        boolean baseExistente;

        try (Connection c = DriverManager.getConnection(url(d, "postgres"), d.superusuario(), d.claveSuperusuario());
             Statement s = c.createStatement()) {
            if (existe(c, "SELECT 1 FROM pg_roles WHERE rolname = ?", USUARIO_APP)) {
                s.execute("ALTER ROLE " + USUARIO_APP + " WITH LOGIN NOSUPERUSER PASSWORD '" + claveBd + "'");
            } else {
                s.execute("CREATE ROLE " + USUARIO_APP + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD '"
                        + claveBd + "'");
            }
            baseExistente = existe(c, "SELECT 1 FROM pg_database WHERE datname = ?", d.baseDatos());
            if (baseExistente) {
                s.execute("ALTER DATABASE " + d.baseDatos() + " OWNER TO " + USUARIO_APP);
            } else {
                s.execute("CREATE DATABASE " + d.baseDatos() + " OWNER " + USUARIO_APP + " ENCODING 'UTF8'");
            }
        }

        try (Connection c = DriverManager.getConnection(url(d, d.baseDatos()), d.superusuario(), d.claveSuperusuario());
             Statement s = c.createStatement()) {
            s.execute("ALTER SCHEMA public OWNER TO " + USUARIO_APP);
            if (baseExistente) s.execute(TRANSFERIR_OBJETOS.replace("{rol}", USUARIO_APP));
        }

        DriverManager.getConnection(url(d, d.baseDatos()), USUARIO_APP, claveBd).close();

        guardar(d, claveBd, claveDispositivo);
        return new Resultado(archivo, claveDispositivo, baseExistente);
    }

    private void guardar(Datos d, String claveBd, String claveDispositivo) throws IOException {
        Properties p = Files.isRegularFile(archivo) ? leer(archivo) : new Properties();
        p.setProperty("spring.datasource.url", url(d, d.baseDatos()));
        p.setProperty("spring.datasource.username", USUARIO_APP);
        p.setProperty("spring.datasource.password", claveBd);
        p.setProperty("server.port", String.valueOf(d.puertoWeb()));
        p.setProperty("smartstock.seguridad.admin-password", d.claveAdmin());
        p.setProperty("smartstock.seguridad.operador-password", d.claveOperador());
        p.setProperty("smartstock.seguridad.device-key", claveDispositivo);
        Files.createDirectories(archivo.getParent());
        try (Writer w = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8)) {
            p.store(w, "SmartStock IoT - configuracion de este equipo (generada por el asistente de instalacion)");
        }
    }

    static Properties leer(Path archivo) {
        Properties p = new Properties();
        try (var r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
        }
        return p;
    }

    static String url(Datos d, String base) {
        return "jdbc:postgresql://" + d.host().trim() + ":" + d.puerto() + "/" + base;
    }

    private static boolean existe(Connection c, String sql, String valor) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, valor);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static String aleatoria(int largo) {
        StringBuilder sb = new StringBuilder(largo);
        for (int i = 0; i < largo; i++) sb.append(ALFABETO.charAt(AZAR.nextInt(ALFABETO.length())));
        return sb.toString();
    }
}
