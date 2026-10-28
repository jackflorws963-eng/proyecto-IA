package pe.edu.unaj.smartstock.ia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Procesamiento de texto en espanol para el clasificador y la memoria (sin librerias externas). */
public final class Texto {

    private static final Set<String> VACIAS = Set.of(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "de", "del", "al", "a", "y", "o", "en", "por",
            "para", "con", "que", "se", "me", "mi", "lo", "le", "les", "es", "su", "sus", "este", "esta", "eso",
            "esa", "hay", "favor", "porfa", "porfavor", "puedes", "podrias", "quiero", "dime", "dame", "muestrame",
            "ver", "cual", "cuales", "como", "cuanto", "cuantos", "cuantas", "ya", "tambien", "muy", "mas");

    private Texto() { }

    /** Minusculas, sin tildes ni signos: «¿Qué ERRORES hay?» -> "que errores hay". */
    public static String normalizar(String s) {
        String sinTildes = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }

    /** Raiz aproximada: quita plurales y terminaciones verbales frecuentes ("ventas" -> "vent"). */
    static String raiz(String palabra) {
        String p = palabra;
        for (String fin : new String[]{"aciones", "acion", "amente", "ando", "iendo", "aron", "ieron", "emos", "amos",
                "ados", "adas", "ado", "ada", "ar", "er", "ir", "as", "es", "os", "a", "e", "o", "s"}) {
            if (p.length() - fin.length() >= 4 && p.endsWith(fin)) return p.substring(0, p.length() - fin.length());
        }
        return p;
    }

    /** Palabras significativas (raices) mas pares de palabras consecutivas, que capturan "stock bajo", "da de baja". */
    public static List<String> rasgos(String texto) {
        List<String> palabras = new ArrayList<>();
        for (String w : normalizar(texto).split(" ")) {
            if (w.isBlank() || VACIAS.contains(w)) continue;
            palabras.add(w.chars().allMatch(Character::isDigit) ? "#num" : raiz(w));
        }
        List<String> r = new ArrayList<>(palabras);
        for (int i = 0; i + 1 < palabras.size(); i++) r.add(palabras.get(i) + "_" + palabras.get(i + 1));
        return r;
    }

    /** Distancia de edicion (para tolerar errores de dictado: "arros" ~ "arroz"). */
    static int distancia(String a, String b) {
        int[] previa = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previa[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(Math.min(actual[j - 1] + 1, previa[j] + 1), previa[j - 1] + costo);
            }
            int[] t = previa;
            previa = actual;
            actual = t;
        }
        return previa[b.length()];
    }

    /**
     * Cuanto se parece una frase a un nombre (0 a 1): proporcion de palabras del nombre que aparecen
     * en la frase, aceptando un error de dictado por palabra larga.
     */
    static double parecido(String frase, String nombre) {
        String[] palabrasFrase = normalizar(frase).split(" ");
        String[] palabrasNombre = normalizar(nombre).split(" ");
        int significativas = 0;
        int encontradas = 0;
        for (String n : palabrasNombre) {
            if (n.length() < 3 || VACIAS.contains(n)) continue;
            significativas++;
            for (String f : palabrasFrase) {
                if (f.equals(n) || (n.length() >= 5 && f.length() >= 4 && distancia(f, n) <= 1)
                        || (f.length() >= 4 && n.startsWith(f))) {
                    encontradas++;
                    break;
                }
            }
        }
        return significativas == 0 ? 0 : (double) encontradas / significativas;
    }
}
