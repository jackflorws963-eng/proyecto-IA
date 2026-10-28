package pe.edu.unaj.smartstock.service;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.unaj.smartstock.dto.Dtos.*;
import pe.edu.unaj.smartstock.entity.Alerta;
import pe.edu.unaj.smartstock.entity.Dispositivo;
import pe.edu.unaj.smartstock.entity.Enums.EstadoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.EstadoDiscrepancia;
import pe.edu.unaj.smartstock.entity.Enums.ModoVentilador;
import pe.edu.unaj.smartstock.entity.Enums.PrioridadAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoAlerta;
import pe.edu.unaj.smartstock.entity.Enums.TipoSensor;
import pe.edu.unaj.smartstock.entity.Ubicacion;
import pe.edu.unaj.smartstock.exception.NegocioException;
import pe.edu.unaj.smartstock.repository.AlertaRepository;
import pe.edu.unaj.smartstock.repository.DiscrepanciaRepository;
import pe.edu.unaj.smartstock.repository.DispositivoRepository;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Asistente de diagnostico basado en reglas (funciona sin internet y sin costo).
 *  - diagnosticar(): convierte las alertas activas y el estado de los ventiladores en problemas
 *    con una sugerencia y, cuando se puede, acciones que el asistente sabe ejecutar.
 *  - responder(): entiende ordenes en espanol por palabras clave (texto escrito o dictado por voz).
 *  - ejecutar(): aplica acciones (reconocer alertas, controlar el ventilador).
 * Reparar problemas pide confirmacion; nunca ajusta el stock (las discrepancias siguen con revision humana).
 */
@Service
public class AsistenteService {

    /** Minutos con el ventilador encendido sin que baje la temperatura para sospechar una falla. */
    static final long MINUTOS_SIN_MEJORA = 15;

    /** A igual prioridad, primero lo que dana productos (ambiente), luego el stock y por ultimo la conexion. */
    private static final List<TipoAlerta> URGENCIA = List.of(TipoAlerta.TEMPERATURA, TipoAlerta.HUMEDAD,
            TipoAlerta.DISCREPANCIA, TipoAlerta.STOCK_MINIMO, TipoAlerta.DISPOSITIVO_OFFLINE);

    private static final Comparator<Alerta> POR_PRIORIDAD = Comparator
            .comparing((Alerta a) -> a.getPrioridad().ordinal()).reversed()
            .thenComparing(a -> URGENCIA.indexOf(a.getTipo()))
            .thenComparing(Alerta::getUltimaVez, Comparator.reverseOrder());

    private final AlertaRepository alertas;
    private final AlertaService alertaService;
    private final DispositivoRepository dispositivos;
    private final DispositivoService dispositivoService;
    private final DiscrepanciaRepository discrepancias;
    private final VentiladorService ventilador;
    private final VentaService ventas;

    public AsistenteService(AlertaRepository alertas, AlertaService alertaService, DispositivoRepository dispositivos,
                            DispositivoService dispositivoService, DiscrepanciaRepository discrepancias,
                            VentiladorService ventilador, VentaService ventas) {
        this.alertas = alertas;
        this.alertaService = alertaService;
        this.dispositivos = dispositivos;
        this.dispositivoService = dispositivoService;
        this.discrepancias = discrepancias;
        this.ventilador = ventilador;
        this.ventas = ventas;
    }

    // ======================= DIAGNOSTICO =======================

    @Transactional(readOnly = true)
    public DiagnosticoResponse diagnosticar() {
        List<Problema> problemas = problemas();
        return new DiagnosticoResponse(resumen(problemas), problemas);
    }

    private List<Problema> problemas() {
        List<Problema> r = new ArrayList<>();
        List<Alerta> activas = new ArrayList<>(alertas.findByEstadoNotOrderByUltimaVezDesc(EstadoAlerta.CERRADA));
        activas.sort(POR_PRIORIDAD);
        Set<Long> ubicacionesConAlertaTemp = new HashSet<>();
        for (Alerta a : activas) {
            r.add(problemaDe(a));
            if (a.getTipo() == TipoAlerta.TEMPERATURA) {
                ubicacionesConAlertaTemp.add(idEntidad(a));
            }
        }
        // Ventilador encendido a mano cuando ya no hace falta: gasto de energia
        for (Dispositivo d : dispositivos.findByActivoTrue()) {
            if (d.getVentiladorModo() != ModoVentilador.ENCENDIDO
                    || ubicacionesConAlertaTemp.contains(d.getUbicacion().getId())) continue;
            Optional<BigDecimal> t = ventilador.ultimaLectura(d, TipoSensor.TEMPERATURA);
            if (t.isPresent() && t.get().compareTo(d.getUbicacion().getTempMax().subtract(VentiladorService.HISTERESIS)) <= 0) {
                r.add(new Problema("ventilador:" + d.getId(), "VENTILADOR", PrioridadAlerta.BAJA,
                        "El ventilador de " + d.getCodigo() + " sigue encendido en modo manual con la temperatura normal ("
                                + grados(t.get()) + ")",
                        "Páselo a automático: se encenderá solo cuando vuelva a hacer falta.",
                        List.of(accionVentilador(d, ModoVentilador.AUTO))));
            }
        }
        return r;
    }

    private Problema problemaDe(Alerta a) {
        List<AccionAsistente> acciones = new ArrayList<>();
        String sugerencia = switch (a.getTipo()) {
            case TEMPERATURA -> sugerenciaTemperatura(idEntidad(a), acciones);
            case HUMEDAD -> sugerenciaHumedad(idEntidad(a), acciones);
            case STOCK_MINIMO -> "Registre una entrada en Inventario cuando llegue mercadería o haga el pedido al proveedor.";
            case DISCREPANCIA -> "Un administrador debe revisar la discrepancia en Dispositivos IoT y aprobar o rechazar "
                    + "el ajuste. El asistente no cambia el stock por su cuenta.";
            case DISPOSITIVO_OFFLINE -> "Revise que el ESP32 tenga energía y Wi-Fi, que la IP del servidor en secrets.h "
                    + "sea la de esta PC y que el firewall de Windows permita el puerto 8080.";
        };
        if (a.getEstado() == EstadoAlerta.ABIERTA) {
            acciones.add(new AccionAsistente("RECONOCER:" + a.getId(), "Marcar como vista la alerta: " + a.getMensaje()));
        }
        return new Problema("alerta:" + a.getId(), a.getTipo().name(), a.getPrioridad(), a.getMensaje(), sugerencia, acciones);
    }

    private String sugerenciaTemperatura(Long ubicacionId, List<AccionAsistente> acciones) {
        List<Dispositivo> equipos = dispositivosDe(ubicacionId);
        Optional<Ubicacion> u = equipos.stream().map(Dispositivo::getUbicacion).findFirst();
        Optional<BigDecimal> t = equipos.stream()
                .map(d -> ventilador.ultimaLectura(d, TipoSensor.TEMPERATURA)).flatMap(Optional::stream).findFirst();
        if (u.isEmpty() || t.isEmpty()) {
            return "No hay lecturas recientes de temperatura en esa ubicación: revise el sensor DHT22.";
        }
        if (t.get().compareTo(u.get().getTempMin()) < 0) {
            equipos.stream().filter(Dispositivo::isVentiladorEncendido)
                    .forEach(d -> acciones.add(accionVentilador(d, ModoVentilador.APAGADO)));
            return "Hace demasiado frío (" + grados(t.get()) + "). Apague el ventilador si está encendido y revise "
                    + "la climatización del almacén.";
        }
        if (t.get().compareTo(u.get().getTempMax()) <= 0) {
            return "La temperatura ya volvió al rango (" + grados(t.get()) + "). La alerta se cerrará sola al estabilizarse.";
        }
        StringBuilder s = new StringBuilder("Exceso de temperatura (" + grados(t.get()) + ", máximo "
                + grados(u.get().getTempMax()) + "). ");
        for (Dispositivo d : equipos) {
            long minutos = d.getVentiladorCambio() == null ? 0
                    : Duration.between(d.getVentiladorCambio(), OffsetDateTime.now()).toMinutes();
            if (!d.isVentiladorEncendido()) {
                acciones.add(accionVentilador(d, ModoVentilador.ENCENDIDO));
            } else if (minutos >= MINUTOS_SIN_MEJORA) {
                s.append("El ventilador de ").append(d.getCodigo()).append(" lleva más de ").append(MINUTOS_SIN_MEJORA)
                        .append(" minutos encendido y la temperatura no baja: verifique que gire y que el relé funcione. ");
            } else {
                s.append("El ventilador de ").append(d.getCodigo()).append(" ya está encendido")
                        .append(d.getVentiladorModo() == ModoVentilador.AUTO ? " (se activó automáticamente)" : "")
                        .append("; esperemos a que la temperatura baje. ");
            }
        }
        if (!acciones.isEmpty()) s.append("Puedo encender el ventilador. ");
        return s.append("Revise también puertas abiertas, fuentes de calor y la ventilación; traslade los productos "
                + "sensibles si sigue subiendo.").toString();
    }

    private String sugerenciaHumedad(Long ubicacionId, List<AccionAsistente> acciones) {
        List<Dispositivo> equipos = dispositivosDe(ubicacionId);
        Optional<Ubicacion> u = equipos.stream().map(Dispositivo::getUbicacion).findFirst();
        Optional<BigDecimal> h = equipos.stream()
                .map(d -> ventilador.ultimaLectura(d, TipoSensor.HUMEDAD)).flatMap(Optional::stream).findFirst();
        if (u.isEmpty() || h.isEmpty()) return "No hay lecturas recientes de humedad en esa ubicación: revise el sensor DHT22.";
        if (h.get().compareTo(u.get().getHumMax()) > 0) {
            equipos.stream().filter(d -> !d.isVentiladorEncendido())
                    .forEach(d -> acciones.add(accionVentilador(d, ModoVentilador.ENCENDIDO)));
            return "Humedad alta (" + porcentaje(h.get()) + "). Encender el ventilador ayuda a circular el aire; "
                    + "revise también filtraciones y no deje productos en el piso.";
        }
        if (h.get().compareTo(u.get().getHumMin()) < 0) {
            return "Humedad baja (" + porcentaje(h.get()) + "). Revise la climatización; los productos sensibles pueden resecarse.";
        }
        return "La humedad ya volvió al rango (" + porcentaje(h.get()) + "). La alerta se cerrará sola al estabilizarse.";
    }

    private static String resumen(List<Problema> problemas) {
        if (problemas.isEmpty()) return "Todo está en orden: no hay alertas activas.";
        long altas = problemas.stream().filter(p -> p.gravedad() == PrioridadAlerta.ALTA).count();
        long reparables = problemas.stream().filter(p -> !p.acciones().isEmpty()).count();
        StringBuilder s = new StringBuilder(problemas.size() == 1 ? "Encontré un problema" : "Encontré " + problemas.size() + " problemas");
        if (altas > 0) s.append(altas == 1 ? ", uno de prioridad alta" : ", " + altas + " de prioridad alta");
        s.append(". El más importante: ").append(problemas.get(0).titulo()).append('.');
        if (reparables > 0) s.append(" Puedo ayudarte con ").append(reparables == 1 ? "uno de ellos" : reparables + " de ellos")
                .append(" si me dices «repara los problemas».");
        return s.toString();
    }

    // ======================= CONVERSACION =======================

    private static final Pattern REPARAR = patron("repar|soluciona|arregl|corrig|resuelv|haz algo|arreglalo");
    private static final Pattern RECONOCER = patron("reconoc|ya (lo|las) vi|marca(r)? (como )?vist");
    private static final Pattern APAGAR = patron("apag|desactiv|deten|^para |quita");
    private static final Pattern ENCENDER = patron("encend|enciend|prend|activ|pon |pone|enfria|baja la temperatura");
    private static final Pattern AUTOMATICO = patron("automat|auto\\b");
    private static final Pattern AMBIENTE = patron("temperatura|humedad|clima|calor|frio|ambiente|grados");
    private static final Pattern VENTAS = patron("venta|vend|caja|ingreso|recaud|factur|boleta");
    private static final Pattern STOCK = patron("stock|inventario|agot|minimo|producto|existencia");
    private static final Pattern DISPOSITIVOS = patron("dispositivo|conexion|conectad|esp32|sensor|wifi");
    private static final Pattern DISCREPANCIAS = patron("discrepanc|diferencia|peso");
    private static final Pattern DIAGNOSTICO = patron("error|problema|falla|alerta|diagnost|estado|pasa|revis|novedad|todo bien|como esta|como va|resumen|reporte");
    private static final Pattern AYUDA = patron("ayuda|que puedes|que sabes|comandos|como funciona");
    private static final Pattern SALUDO = patron("^(hola|buen(os|as)|que tal|saludos)");
    private static final Pattern GRACIAS = patron("gracias|genial|perfecto|excelente");
    private static final Pattern SI_NO = patron("^(si|no|claro|dale|ok|okey|confirmo|cancela|hazlo)$");

    /** Sin transaccion propia: las ordenes al ventilador abren la suya (una de solo lectura no guardaria cambios). */
    public RespuestaAsistente responder(String texto, Authentication auth) {
        String t = normalizar(texto);
        if (t.isBlank()) return solo("No escuché nada. ¿Puedes repetirlo?");
        if (AYUDA.matcher(t).find()) return solo(ayuda());
        if (t.contains("ventilador") || t.contains("ventilacion")) return ordenVentilador(t, auth);
        if (REPARAR.matcher(t).find()) return proponerReparacion();
        if (RECONOCER.matcher(t).find()) return proponerReconocer();
        if (SI_NO.matcher(t).matches()) return solo("No tengo ninguna acción pendiente. Pregúntame «¿qué errores hay?».");
        if (AMBIENTE.matcher(t).find()) return solo(ambiente());
        if (VENTAS.matcher(t).find()) return solo(resumenVentas());
        if (DISCREPANCIAS.matcher(t).find()) return porTipo("DISCREPANCIA", "No hay discrepancias de peso pendientes.");
        if (STOCK.matcher(t).find()) return porTipo("STOCK_MINIMO", "Ningún producto está por debajo de su stock mínimo.");
        if (DISPOSITIVOS.matcher(t).find()) return solo(estadoDispositivos());
        if (DIAGNOSTICO.matcher(t).find()) {
            List<Problema> p = problemas();
            return new RespuestaAsistente(resumen(p), p, List.of());
        }
        if (SALUDO.matcher(t).find()) return solo("Hola, soy el asistente de SmartStock. " + resumen(problemas()));
        if (GRACIAS.matcher(t).find()) return solo("De nada. Sigo vigilando el almacén.");
        return solo("No entendí. Puedes preguntarme «¿qué errores hay?», «¿cómo está la temperatura?», "
                + "«repara los problemas» o «enciende el ventilador». Di «ayuda» para ver todo lo que sé hacer.");
    }

    private RespuestaAsistente proponerReparacion() {
        List<Problema> p = problemas();
        if (p.isEmpty()) return solo("No hay nada que reparar: no hay alertas activas.");
        Map<String, AccionAsistente> acciones = new LinkedHashMap<>();
        // Primero las acciones que resuelven (ventilador) y despues las de reconocer alertas
        p.stream().flatMap(x -> x.acciones().stream()).filter(a -> a.codigo().startsWith("VENTILADOR"))
                .forEach(a -> acciones.putIfAbsent(a.codigo(), a));
        p.stream().flatMap(x -> x.acciones().stream()).forEach(a -> acciones.putIfAbsent(a.codigo(), a));
        // Problemas que el software no puede resolver: alguien debe revisarlos en persona
        long manuales = p.stream().filter(x -> Set.of("STOCK_MINIMO", "DISCREPANCIA", "DISPOSITIVO_OFFLINE")
                .contains(x.tipo())).count();
        if (acciones.isEmpty()) {
            return new RespuestaAsistente("No puedo resolver estos problemas automáticamente; te dejo qué revisar en cada uno.", p, List.of());
        }
        // Texto corto para leerlo en voz alta; el detalle de cada accion queda en porConfirmar
        List<String> partes = new ArrayList<>(acciones.values().stream().map(AccionAsistente::descripcion)
                .filter(d -> !d.startsWith("Marcar como vista")).map(AsistenteService::minuscula).toList());
        long vistas = acciones.keySet().stream().filter(c -> c.startsWith("RECONOCER")).count();
        if (vistas > 0) partes.add(vistas == 1 ? "marcar una alerta como vista" : "marcar " + vistas + " alertas como vistas");
        String texto = "Puedo " + String.join(", ", partes) + ". ¿Lo hago?"
                + (manuales == 0 ? "" : manuales == 1 ? " Además, un problema necesita que alguien lo revise en persona."
                        : " Además, " + manuales + " problemas necesitan que alguien los revise en persona.");
        return new RespuestaAsistente(texto, p, List.copyOf(acciones.values()));
    }

    private RespuestaAsistente proponerReconocer() {
        List<AccionAsistente> acciones = alertas.findByEstadoNotOrderByUltimaVezDesc(EstadoAlerta.CERRADA).stream()
                .filter(a -> a.getEstado() == EstadoAlerta.ABIERTA)
                .map(a -> new AccionAsistente("RECONOCER:" + a.getId(), "Marcar como vista: " + a.getMensaje())).toList();
        if (acciones.isEmpty()) return solo("No hay alertas nuevas por reconocer.");
        return new RespuestaAsistente("Hay " + acciones.size() + (acciones.size() == 1 ? " alerta nueva" : " alertas nuevas")
                + ". ¿Las marco como vistas? Seguirán activas hasta que el problema se normalice.", List.of(), acciones);
    }

    /** Ordenes directas: «enciende el ventilador», «apaga el ventilador del ESP32-01», «ventilador en automatico». */
    private RespuestaAsistente ordenVentilador(String t, Authentication auth) {
        List<Dispositivo> todos = dispositivos.findByActivoTrue();
        List<Dispositivo> elegidos = todos.stream().filter(d -> t.contains(normalizar(d.getCodigo()))
                || t.contains(normalizar(d.getUbicacion().getNombre()))).toList();
        if (elegidos.isEmpty()) elegidos = todos;
        if (elegidos.isEmpty()) return solo("No hay dispositivos activos con ventilador.");

        ModoVentilador modo = AUTOMATICO.matcher(t).find() ? ModoVentilador.AUTO
                : APAGAR.matcher(t).find() ? ModoVentilador.APAGADO
                : ENCENDER.matcher(t).find() ? ModoVentilador.ENCENDIDO : null;
        if (modo == null) {
            return solo(String.join(" ", elegidos.stream().map(d -> "El ventilador de " + d.getCodigo() + " está "
                    + (d.isVentiladorEncendido() ? "encendido" : "apagado")
                    + (d.getVentiladorModo() == ModoVentilador.AUTO ? ", en modo automático." : ", por orden manual.")).toList()));
        }
        if (!esAdmin(auth)) return solo("Solo un administrador puede controlar el ventilador.");
        // Orden explicita del usuario: se ejecuta sin pedir confirmacion
        List<AccionAsistente> acciones = elegidos.stream().map(d -> accionVentilador(d, modo)).toList();
        return new RespuestaAsistente(ejecutarCodigos(acciones.stream().map(AccionAsistente::codigo).toList(), auth),
                List.of(), List.of());
    }

    // ======================= ACCIONES =======================

    /** Ejecuta acciones confirmadas por el usuario. Cada una se valida otra vez (los codigos vienen del navegador). */
    public RespuestaAsistente ejecutar(List<String> codigos, Authentication auth) {
        String texto = ejecutarCodigos(codigos, auth);
        List<Problema> p = problemas();
        return new RespuestaAsistente(texto + " " + resumen(p), p, List.of());
    }

    private String ejecutarCodigos(List<String> codigos, Authentication auth) {
        List<String> hechos = new ArrayList<>();
        List<String> fallidos = new ArrayList<>();
        for (String codigo : new LinkedHashSet<>(codigos)) {
            String[] partes = codigo.split(":");
            try {
                switch (partes[0]) {
                    case "RECONOCER" -> {
                        alertaService.reconocer(Long.valueOf(partes[1]), auth.getName());
                        hechos.add("marqué una alerta como vista");
                    }
                    case "VENTILADOR" -> {
                        if (!esAdmin(auth)) throw NegocioException.invalido("solo un administrador controla el ventilador");
                        ModoVentilador modo = ModoVentilador.valueOf(partes[2]);
                        DispositivoResponse d = ventilador.cambiarModo(Long.valueOf(partes[1]), modo);
                        hechos.add(switch (modo) {
                            case ENCENDIDO -> "encendí el ventilador de " + d.codigo();
                            case APAGADO -> "apagué el ventilador de " + d.codigo();
                            case AUTO -> "puse el ventilador de " + d.codigo() + " en automático (ahora está "
                                    + (d.ventiladorEncendido() ? "encendido" : "apagado") + ")";
                        });
                    }
                    default -> throw NegocioException.invalido("acción desconocida " + codigo);
                }
            } catch (NegocioException e) {
                fallidos.add(e.getMessage());
            } catch (RuntimeException e) {
                fallidos.add("acción inválida " + codigo);
            }
        }
        StringBuilder s = new StringBuilder();
        if (!hechos.isEmpty()) s.append("Listo: ").append(agrupar(hechos)).append('.');
        if (!fallidos.isEmpty()) s.append(s.isEmpty() ? "" : " ").append("No pude: ").append(String.join("; ", fallidos)).append('.');
        if (hechos.stream().anyMatch(h -> h.contains("ventilador"))) {
            s.append(" El ESP32 recibirá la orden en su próximo envío, en unos segundos.");
        }
        return s.toString();
    }

    // ======================= CONSULTAS =======================

    private String ambiente() {
        List<Dispositivo> activos = dispositivos.findByActivoTrue();
        if (activos.isEmpty()) return "No hay dispositivos activos midiendo el ambiente.";
        List<String> partes = new ArrayList<>();
        for (Dispositivo d : activos) {
            Ubicacion u = d.getUbicacion();
            Optional<BigDecimal> t = ventilador.ultimaLectura(d, TipoSensor.TEMPERATURA);
            Optional<BigDecimal> h = ventilador.ultimaLectura(d, TipoSensor.HUMEDAD);
            if (t.isEmpty() && h.isEmpty()) {
                partes.add("En " + u.getNombre() + " aún no hay lecturas de " + d.getCodigo() + ".");
                continue;
            }
            StringBuilder s = new StringBuilder("En " + u.getNombre() + ": ");
            t.ifPresent(v -> s.append(grados(v)).append(fueraDeRango(v, u.getTempMin(), u.getTempMax())));
            h.ifPresent(v -> s.append(t.isPresent() ? " y humedad de " : "humedad de ").append(porcentaje(v))
                    .append(fueraDeRango(v, u.getHumMin(), u.getHumMax())));
            s.append(". Ventilador ").append(d.isVentiladorEncendido() ? "encendido" : "apagado").append('.');
            partes.add(s.toString());
        }
        return String.join(" ", partes);
    }

    private String resumenVentas() {
        ResumenVentas r = ventas.resumen();
        StringBuilder s = new StringBuilder(r.ventasHoy() == 0 ? "Hoy todavía no hay ventas. "
                : "Hoy llevamos " + r.ventasHoy() + (r.ventasHoy() == 1 ? " venta" : " ventas") + " por " + soles(r.totalHoy())
                  + ", con un ticket promedio de " + soles(r.ticketPromedioHoy()) + ". ");
        s.append("En el mes: ").append(r.ventasMes()).append(" ventas por ").append(soles(r.totalMes())).append('.');
        if (!r.masVendidosMes().isEmpty()) {
            s.append(" El producto que más vende es ").append(r.masVendidosMes().get(0).nombre()).append('.');
        }
        return s.toString();
    }

    private static String soles(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + " soles";
    }

    private String estadoDispositivos() {
        List<Dispositivo> activos = dispositivos.findByActivoTrue();
        if (activos.isEmpty()) return "No hay dispositivos activos registrados.";
        return String.join(" ", activos.stream().map(d -> d.getCodigo() + " (" + d.getUbicacion().getNombre() + ") está "
                + (dispositivoService.enLinea(d) ? "en línea." : "sin conexión.")).toList());
    }

    private RespuestaAsistente porTipo(String tipo, String siNoHay) {
        List<Problema> p = problemas().stream().filter(x -> x.tipo().equals(tipo)).toList();
        if (p.isEmpty()) return solo(siNoHay);
        String texto = String.join(" ", p.stream().map(Problema::titulo).toList());
        if (tipo.equals("DISCREPANCIA")) {
            texto = discrepancias.countByEstado(EstadoDiscrepancia.PENDIENTE) + " discrepancias pendientes. " + texto;
        }
        return new RespuestaAsistente(texto + " " + p.get(0).sugerencia(), p, List.of());
    }

    private static String ayuda() {
        return "Puedo decirte qué errores hay ahora, cómo está la temperatura y la humedad, qué productos tienen "
                + "stock bajo, cuánto se vendió hoy y en el mes, si los dispositivos están conectados y si hay discrepancias de peso. Para reparar, "
                + "di «repara los problemas»: te propondré acciones como encender el ventilador o marcar alertas como "
                + "vistas, y las haré cuando confirmes. También puedes ordenar «enciende el ventilador», «apágalo» o "
                + "«ventilador en automático».";
    }

    // ======================= UTILIDADES =======================

    private List<Dispositivo> dispositivosDe(Long ubicacionId) {
        return dispositivos.findByActivoTrue().stream()
                .filter(d -> d.getUbicacion().getId().equals(ubicacionId)).toList();
    }

    private static AccionAsistente accionVentilador(Dispositivo d, ModoVentilador modo) {
        String que = switch (modo) {
            case ENCENDIDO -> "Encender el ventilador de ";
            case APAGADO -> "Apagar el ventilador de ";
            case AUTO -> "Poner en automático el ventilador de ";
        };
        return new AccionAsistente("VENTILADOR:" + d.getId() + ":" + modo, que + d.getCodigo());
    }

    /** La clave de la alerta tiene la forma TIPO:entidad:id. */
    private static Long idEntidad(Alerta a) {
        String[] p = a.getClaveDedup().split(":");
        try {
            return Long.valueOf(p[p.length - 1]);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean esAdmin(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(g -> g.getAuthority().equals("ROLE_ADMIN"));
    }

    private static String fueraDeRango(BigDecimal v, BigDecimal min, BigDecimal max) {
        if (v.compareTo(max) > 0) return " (por encima del máximo " + max.stripTrailingZeros().toPlainString() + ")";
        if (v.compareTo(min) < 0) return " (por debajo del mínimo " + min.stripTrailingZeros().toPlainString() + ")";
        return "";
    }

    private static String grados(BigDecimal v) {
        return v.setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + " grados";
    }

    private static String porcentaje(BigDecimal v) {
        return v.setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + " por ciento";
    }

    /** "marqué una alerta como vista" x3 -> "marqué 3 alertas como vistas". */
    private static String agrupar(List<String> hechos) {
        long vistas = hechos.stream().filter(h -> h.startsWith("marqué una alerta")).count();
        List<String> r = new ArrayList<>(hechos.stream().filter(h -> !h.startsWith("marqué una alerta")).toList());
        if (vistas == 1) r.add("marqué una alerta como vista");
        else if (vistas > 1) r.add("marqué " + vistas + " alertas como vistas");
        return String.join(", ", r);
    }

    private static String minuscula(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static RespuestaAsistente solo(String texto) {
        return new RespuestaAsistente(texto, List.of(), List.of());
    }

    /** Minusculas, sin tildes y sin signos: «¿Qué ERRORES hay?» -> "que errores hay". */
    static String normalizar(String s) {
        String sinTildes = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static Pattern patron(String regex) {
        return Pattern.compile(regex);
    }
}
