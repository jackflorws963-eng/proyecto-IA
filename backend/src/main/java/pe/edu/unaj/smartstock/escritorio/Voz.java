package pe.edu.unaj.smartstock.escritorio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

final class Voz {

    static final String ESCUCHANDO = "__ESCUCHANDO__";

    private static final List<String> FRASES = List.of(
            "qué errores hay", "qué problemas hay", "hay errores", "hay problemas", "diagnóstico", "cómo está el almacén",
            "cómo está la temperatura", "cómo está la humedad", "repara los problemas", "repara", "soluciona los problemas",
            "arregla los problemas", "enciende el ventilador", "prende el ventilador", "apaga el ventilador",
            "ventilador en automático", "estado del ventilador", "reconoce las alertas", "estado de los dispositivos",
            "qué productos tienen stock bajo", "stock bajo", "discrepancias", "ayuda", "sí", "no", "confirmo",
            "cancela", "gracias", "hola", "cuánto vendimos hoy", "ventas del día", "ventas del mes");

    private static final String SCRIPT_HABLAR = """
            Add-Type -AssemblyName System.Speech
            $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            $v = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'es*' } | Select-Object -First 1
            if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }
            $s.Rate = 1
            $s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s')))
            """;

    private static final String SCRIPT_ESCUCHAR = """
            $ErrorActionPreference = 'Stop'
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            Add-Type -AssemblyName System.Speech
            $info = [System.Speech.Recognition.SpeechRecognitionEngine]::InstalledRecognizers() |
                Where-Object { $_.Culture.Name -like 'es*' } | Select-Object -First 1
            if (-not $info) { throw 'No hay reconocimiento de voz en espanol instalado en Windows' }
            $r = New-Object System.Speech.Recognition.SpeechRecognitionEngine($info)
            $frases = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('%s')).Split('|')
            $gb = New-Object System.Speech.Recognition.GrammarBuilder
            $gb.Culture = $info.Culture
            $gb.Append((New-Object System.Speech.Recognition.Choices(,[string[]]$frases)))
            $r.LoadGrammar((New-Object System.Speech.Recognition.Grammar($gb)))
            $dictado = New-Object System.Speech.Recognition.DictationGrammar
            $dictado.Weight = 0.3
            $r.LoadGrammar($dictado)
            $r.SetInputToDefaultAudioDevice()
            $r.InitialSilenceTimeout = [TimeSpan]::FromSeconds(7)
            $r.EndSilenceTimeout = [TimeSpan]::FromSeconds(0.8)
            [Console]::Out.WriteLine('%s'); [Console]::Out.Flush()
            $res = $r.Recognize([TimeSpan]::FromSeconds(15))
            if ($res) { [Console]::Out.WriteLine($res.Text) }
            """;

    private Process hablando;
    private volatile Process escuchando;

    static boolean disponible() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    synchronized void hablar(String texto) {
        callar();
        String recortado = texto.length() > 1500 ? texto.substring(0, 1500) : texto;
        try {
            hablando = iniciar(SCRIPT_HABLAR.formatted(base64(recortado)));
        } catch (IOException e) {
            hablando = null;
        }
    }

    synchronized void callar() {
        if (hablando != null) hablando.destroyForcibly();
        hablando = null;
    }

    synchronized void escuchar(Runnable alEscuchar, Consumer<String> alTerminar, Consumer<String> alFallar) {
        callar();
        if (escuchando != null) escuchando.destroyForcibly();
        Process p;
        try {
            p = iniciar(SCRIPT_ESCUCHAR.formatted(base64(String.join("|", FRASES)), ESCUCHANDO));
            escuchando = p;
        } catch (IOException e) {
            alFallar.accept("No se pudo iniciar el reconocimiento de voz: " + e.getMessage());
            return;
        }
        Thread hilo = new Thread(() -> {
            StringBuilder texto = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String linea;
                while ((linea = in.readLine()) != null) {
                    if (linea.contains(ESCUCHANDO)) alEscuchar.run();
                    else texto.append(linea.trim());
                }
                if (p.waitFor() != 0 && p == escuchando) {
                    alFallar.accept("No se pudo usar el micrófono. Revise que esté conectado y que Windows permita "
                            + "el acceso al micrófono (Configuración > Privacidad > Micrófono).");
                } else if (p == escuchando) {
                    alTerminar.accept(texto.toString());
                }
            } catch (IOException | InterruptedException e) {
                alTerminar.accept("");
            }
        }, "voz-escuchar");
        hilo.setDaemon(true);
        hilo.start();
    }

    synchronized void cerrar() {
        callar();
        if (escuchando != null) escuchando.destroyForcibly();
    }

    private static Process iniciar(String script) throws IOException {
        String codificado = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", codificado)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static String base64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
