/*
 * SmartStock IoT - Firmware ESP32
 * ------------------------------------------------------------------
 * Lee DHT22 (temperatura, humedad) y celda de carga + HX711 (peso),
 * guarda las mediciones en un buffer local y las envia por lotes a la
 * API REST (POST /api/iot/lecturas). Si no hay Wi-Fi o el servidor no
 * responde, las mediciones quedan pendientes y se reenvian luego.
 *
 * Proteccion contra duplicados: cada ciclo de medicion tiene un numero
 * de secuencia (seq) que NUNCA se repite, ni siquiera tras reiniciar
 * (se reserva en la memoria flash NVS). El servidor ignora (sensor, seq)
 * repetidos y responde DUPLICADA, asi un reintento no duplica datos.
 *
 * Bibliotecas (Arduino IDE > Gestor de bibliotecas):
 *   - "DHT sensor library" (Adafruit) + "Adafruit Unified Sensor"
 *   - "HX711" (Bogdan Necula, bogde)
 *   - "ArduinoJson" (Benoit Blanchon) version 7.x
 * Placa: "ESP32 Dev Module" (core esp32 de Espressif 2.x o 3.x).
 *
 * Conexiones:
 *   DHT22  VCC->3V3  GND->GND  DATA->GPIO4 (resistencia 10k a 3V3 si el modulo no la trae)
 *   HX711  VCC->3V3  GND->GND  DT->GPIO16  SCK->GPIO17
 *   Celda  rojo->E+  negro->E-  blanco->A-  verde->A+
 *   Rele   VCC->5V(VIN)  GND->GND  IN->GPIO26  (modulo rele de 1 canal; el ventilador va en COM/NO)
 *
 * Ventilador: el servidor indica en cada respuesta si debe estar encendido (campo "ventilador":
 * modo automatico por temperatura, o la orden del usuario / asistente). Si el servidor no responde
 * durante VENTILADOR_SIN_SERVIDOR_MS, el ESP32 decide solo con TEMP_SEGURIDAD_C (respaldo local).
 */

#include <WiFi.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <DHT.h>
#include <HX711.h>
#include <time.h>
#include "secrets.h"   // crear a partir de secrets.h.example

// ---------------- Configuracion ----------------
const uint8_t PIN_DHT = 4;
const uint8_t PIN_HX_DT = 16;
const uint8_t PIN_HX_SCK = 17;
const uint8_t PIN_RELE = 26;
const bool RELE_ACTIVO_EN_BAJO = true;          // la mayoria de modulos rele se activan con LOW
const float TEMP_SEGURIDAD_C = 30.0f;          // respaldo local: enciende sobre esta temperatura
const uint32_t VENTILADOR_SIN_SERVIDOR_MS = 600000;   // 10 min sin respuesta -> respaldo local

const uint32_t PERIODO_MEDICION_MS = 5000;   // una medicion cada 5 s
const uint32_t PERIODO_ENVIO_MS    = 5000;   // intento de envio cada 5 s
const uint16_t MAX_POR_LOTE        = 30;     // lecturas por POST (el servidor acepta hasta 100)
const uint16_t CAPACIDAD_BUFFER    = 360;    // ~10 min de mediciones (3 por ciclo) sin conexion
const uint8_t  MUESTRAS_PESO       = 10;     // promedio de 10 muestras del HX711
const uint16_t HTTP_TIMEOUT_MS     = 6000;
const uint32_t SEQ_BLOQUE          = 100;    // reserva de seq en flash (reduce escrituras)

// ---------------- Estructuras ----------------
enum TipoSensor : uint8_t { TEMPERATURA = 0, HUMEDAD = 1, PESO = 2 };
const char* NOMBRE_TIPO[] = { "TEMPERATURA", "HUMEDAD", "PESO" };

struct Medicion {
  uint32_t seq;
  uint32_t ts;     // epoch UTC (0 si aun no hay hora NTP)
  float valor;
  uint8_t tipo;
};

// Buffer circular de mediciones pendientes
Medicion buffer[CAPACIDAD_BUFFER];
uint16_t inicio = 0, cantidad = 0;
uint32_t descartadasPorBufferLleno = 0;

DHT dht(PIN_DHT, DHT22);
HX711 balanza;
Preferences prefs;

uint32_t seqActual = 0;
uint32_t seqReservadoHasta = 0;
uint32_t ultimaMedicion = 0, ultimoEnvio = 0, ultimoIntentoWifi = 0;
uint32_t esperaReintentoMs = PERIODO_ENVIO_MS;   // crece si el servidor falla (backoff)

// Estadisticas para la evaluacion experimental (se imprimen por Serial)
uint32_t enviosOk = 0, enviosFallidos = 0, lecturasConfirmadas = 0;
uint32_t momentoDesconexion = 0;

// Ventilador
bool ventiladorEncendido = false;
float ultimaTemperatura = NAN;
uint32_t ultimaRespuestaServidor = 0;

// ---------------- Secuencia persistente ----------------
uint32_t siguienteSeq() {
  seqActual++;
  if (seqActual >= seqReservadoHasta) {
    seqReservadoHasta = seqActual + SEQ_BLOQUE;
    prefs.putUInt("seqMax", seqReservadoHasta);   // 1 escritura flash cada 100 ciclos
  }
  return seqActual;
}

// Si el servidor ya tiene un seq mayor (se borro la flash o se cambio la placa),
// se continua desde ahi para no reutilizar numeros ya registrados.
void ajustarSeqDesdeServidor(uint32_t seqServidor) {
  if (seqServidor <= seqActual) return;   // caso normal: no escribe en flash
  Serial.printf("[Seq] El servidor ya tiene seq %lu (local %lu): se continua desde ahi\n",
                (unsigned long)seqServidor, (unsigned long)seqActual);
  seqActual = seqServidor;
  seqReservadoHasta = seqActual + SEQ_BLOQUE;
  prefs.putUInt("seqMax", seqReservadoHasta);
}

// ---------------- Cero de la balanza persistente ----------------
// El cero (offset del HX711) se guarda en flash. Asi, si el ESP32 se reinicia con
// producto encima (corte de luz), NO toma ese peso como cero.
void ajustarCeroBalanza() {
  if (!balanza.wait_ready_timeout(2000)) {
    Serial.println("[HX711] No responde: no se pudo ajustar el cero");
    return;
  }
  balanza.tare(20);
  prefs.putLong("hxOffset", balanza.get_offset());
  Serial.printf("[HX711] Cero ajustado y guardado (offset %ld)\n", (long)balanza.get_offset());
}

// Comandos por Monitor Serie: 'z' = ajustar cero con la balanza VACIA
void atenderSerial() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == 'z' || c == 'Z') ajustarCeroBalanza();
  }
}

// ---------------- Buffer ----------------
void encolar(uint8_t tipo, float valor, uint32_t seq, uint32_t ts) {
  if (cantidad == CAPACIDAD_BUFFER) {        // lleno: se descarta la mas antigua
    inicio = (inicio + 1) % CAPACIDAD_BUFFER;
    cantidad--;
    descartadasPorBufferLleno++;
  }
  uint16_t pos = (inicio + cantidad) % CAPACIDAD_BUFFER;
  buffer[pos] = { seq, ts, valor, tipo };
  cantidad++;
}

void quitarPrimeras(uint16_t n) {
  if (n > cantidad) n = cantidad;
  inicio = (inicio + n) % CAPACIDAD_BUFFER;
  cantidad -= n;
}

// ---------------- Hora ----------------
uint32_t ahoraEpoch() {
  time_t t = time(nullptr);
  return (t > 1700000000) ? (uint32_t)t : 0;   // antes de sincronizar NTP, time() vale ~0
}

// ---------------- Wi-Fi ----------------
void conectarWifi() {
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.printf("[WiFi] Conectando a %s...\n", WIFI_SSID);
}

void vigilarWifi() {
  static bool estabaConectado = false;
  bool conectado = WiFi.status() == WL_CONNECTED;
  if (conectado && !estabaConectado) {
    Serial.printf("[WiFi] Conectado. IP %s\n", WiFi.localIP().toString().c_str());
    configTime(0, 0, "pool.ntp.org", "time.google.com");   // hora UTC
    if (momentoDesconexion != 0) {
      Serial.printf("[WiFi] Reconectado tras %lu ms\n", (unsigned long)(millis() - momentoDesconexion));
      momentoDesconexion = 0;
    }
  }
  if (!conectado && estabaConectado) {
    Serial.println("[WiFi] Conexion perdida. Las mediciones quedan en el buffer.");
    momentoDesconexion = millis();
  }
  if (!conectado && millis() - ultimoIntentoWifi > 15000) {   // reintento manual cada 15 s
    ultimoIntentoWifi = millis();
    WiFi.disconnect();
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  }
  estabaConectado = conectado;
}

// ---------------- Ventilador (rele) ----------------
void aplicarVentilador(bool encender, const char* motivo) {
  if (encender != ventiladorEncendido) {
    Serial.printf("[Ventilador] %s (%s)\n", encender ? "ENCENDIDO" : "APAGADO", motivo);
  }
  ventiladorEncendido = encender;
  digitalWrite(PIN_RELE, (encender != RELE_ACTIVO_EN_BAJO) ? HIGH : LOW);
}

// Sin servidor por mucho tiempo: el ESP32 protege el almacen por su cuenta (histeresis de 1 grado)
void respaldoVentilador() {
  if (millis() - ultimaRespuestaServidor < VENTILADOR_SIN_SERVIDOR_MS || isnan(ultimaTemperatura)) return;
  if (ultimaTemperatura > TEMP_SEGURIDAD_C) aplicarVentilador(true, "respaldo local: sin servidor");
  else if (ultimaTemperatura < TEMP_SEGURIDAD_C - 1.0f) aplicarVentilador(false, "respaldo local: sin servidor");
}

// ---------------- Sensores ----------------
void medir() {
  uint32_t seq = siguienteSeq();
  uint32_t ts = ahoraEpoch();

  float t = dht.readTemperature();
  float h = dht.readHumidity();
  if (!isnan(t)) { encolar(TEMPERATURA, t, seq, ts); ultimaTemperatura = t; }
  else Serial.println("[DHT22] Error de lectura de temperatura");
  if (!isnan(h)) encolar(HUMEDAD, h, seq, ts);
  else Serial.println("[DHT22] Error de lectura de humedad");

  if (balanza.wait_ready_timeout(1000)) {
    float gramos = balanza.get_units(MUESTRAS_PESO);   // promedio de N muestras
    encolar(PESO, gramos, seq, ts);
    Serial.printf("[Medicion #%lu] T=%.1f C  H=%.1f %%  Peso=%.1f g  (pendientes %u)\n",
                  (unsigned long)seq, t, h, gramos, cantidad);
  } else {
    Serial.println("[HX711] No responde: revise conexiones DT/SCK");
  }
}

// ---------------- Envio ----------------
void enviarLote() {
  if (cantidad == 0 || WiFi.status() != WL_CONNECTED) return;

  uint16_t n = cantidad < MAX_POR_LOTE ? cantidad : MAX_POR_LOTE;
  JsonDocument doc;
  JsonArray arr = doc["lecturas"].to<JsonArray>();
  for (uint16_t i = 0; i < n; i++) {
    const Medicion& m = buffer[(inicio + i) % CAPACIDAD_BUFFER];
    JsonObject o = arr.add<JsonObject>();
    o["tipo"] = NOMBRE_TIPO[m.tipo];
    o["valor"] = serialized(String(m.valor, 3));
    o["seq"] = m.seq;
    if (m.ts > 0) o["ts"] = m.ts;
  }
  String cuerpo;
  serializeJson(doc, cuerpo);

  HTTPClient http;
  http.setTimeout(HTTP_TIMEOUT_MS);
  http.begin(String(SERVER_URL) + "/api/iot/lecturas");
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Code", DEVICE_CODE);
  http.addHeader("X-Device-Key", DEVICE_KEY);

  uint32_t t0 = millis();
  int codigo = http.POST(cuerpo);
  uint32_t latencia = millis() - t0;

  if (codigo == 200) {
    JsonDocument resp;
    DeserializationError err = deserializeJson(resp, http.getString());
    if (!err) {
      // Resultado por lectura (mismo orden del envio):
      //   GUARDADA / DUPLICADA / RECHAZADA -> resuelta, se quita del buffer.
      //   REINTENTAR      -> conflicto temporal: se vuelve a encolar con el mismo seq.
      //   SEQ_REUTILIZADA -> el contador se reinicio: se vuelve a encolar con un seq nuevo.
      static Medicion reencolar[MAX_POR_LOTE];
      bool nuevoSeq[MAX_POR_LOTE];
      uint16_t nReencolar = 0;
      JsonArray resultados = resp["resultados"].as<JsonArray>();
      for (uint16_t i = 0; i < n && i < resultados.size(); i++) {
        const char* r = resultados[i]["resultado"] | "";
        bool reutilizada = strcmp(r, "SEQ_REUTILIZADA") == 0;
        if (reutilizada || strcmp(r, "REINTENTAR") == 0) {
          reencolar[nReencolar] = buffer[(inicio + i) % CAPACIDAD_BUFFER];
          nuevoSeq[nReencolar] = reutilizada;
          nReencolar++;
        }
      }
      quitarPrimeras(n);
      ajustarSeqDesdeServidor(resp["seqMaximo"] | (uint32_t)0);
      ultimaRespuestaServidor = millis();
      aplicarVentilador(resp["ventilador"] | ventiladorEncendido, "orden del servidor");
      for (uint16_t i = 0; i < nReencolar; i++) {
        Medicion& m = reencolar[i];
        encolar(m.tipo, m.valor, nuevoSeq[i] ? siguienteSeq() : m.seq, m.ts);
      }
      lecturasConfirmadas += n - nReencolar;
      enviosOk++;
      esperaReintentoMs = PERIODO_ENVIO_MS;
      Serial.printf("[HTTP] Lote %u: guardadas=%d duplicadas=%d rechazadas=%d reenviar=%u (%lu ms)\n", n,
                    resp["guardadas"].as<int>(), resp["duplicadas"].as<int>(),
                    resp["rechazadas"].as<int>(), nReencolar, (unsigned long)latencia);
    } else {
      Serial.printf("[HTTP] Respuesta ilegible (%s). Se reintentara.\n", err.c_str());
      enviosFallidos++;
    }
  } else if (codigo == 401 || codigo == 403) {
    Serial.println("[HTTP] Credenciales del dispositivo rechazadas: revise DEVICE_CODE / DEVICE_KEY");
    enviosFallidos++;
    esperaReintentoMs = 60000;   // no saturar el servidor con credenciales malas
  } else if (codigo == 400) {
    // Lote mal formado: se descarta para no bloquear la cola indefinidamente
    Serial.printf("[HTTP] 400: %s. Lote descartado.\n", http.getString().c_str());
    quitarPrimeras(n);
    enviosFallidos++;
  } else {
    // Sin respuesta (codigo < 0) o error 5xx: se conserva el lote y se espera mas
    Serial.printf("[HTTP] Error %d (%s). %u lecturas pendientes.\n", codigo,
                  codigo < 0 ? http.errorToString(codigo).c_str() : "servidor", cantidad);
    enviosFallidos++;
    esperaReintentoMs = (esperaReintentoMs * 2 > 60000) ? 60000 : esperaReintentoMs * 2;
  }
  http.end();
}

// ---------------- Arduino ----------------
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\n=== SmartStock IoT - " DEVICE_CODE " ===");

  prefs.begin("smartstock", false);
  seqReservadoHasta = prefs.getUInt("seqMax", 0);
  seqActual = seqReservadoHasta;           // continua despues del ultimo bloque reservado
  Serial.printf("Secuencia inicial: %lu\n", (unsigned long)seqActual);

  pinMode(PIN_RELE, OUTPUT);
  aplicarVentilador(false, "arranque");

  dht.begin();
  balanza.begin(PIN_HX_DT, PIN_HX_SCK);
  balanza.set_scale(HX711_CALIBRATION_FACTOR);
  // La tara del recipiente se configura en la web (calibracion). Aqui solo se usa
  // el cero de la celda vacia, guardado en flash. Solo la PRIMERA vez (flash vacia)
  // se toma el cero al arrancar: hagalo con la balanza vacia. Para repetirlo,
  // vacie la balanza y envie 'z' por el Monitor Serie.
  if (prefs.isKey("hxOffset")) {
    balanza.set_offset(prefs.getLong("hxOffset"));
    Serial.printf("[HX711] Cero restaurado desde flash (offset %ld). Envie 'z' para reajustar.\n",
                  (long)balanza.get_offset());
  } else {
    Serial.println("[HX711] Primer arranque: ajustando cero (la balanza debe estar VACIA)");
    ajustarCeroBalanza();
  }
  conectarWifi();
}

void loop() {
  atenderSerial();
  vigilarWifi();
  uint32_t ahora = millis();
  if (ahora - ultimaMedicion >= PERIODO_MEDICION_MS) {
    ultimaMedicion = ahora;
    medir();
    respaldoVentilador();
  }
  if (ahora - ultimoEnvio >= esperaReintentoMs) {
    ultimoEnvio = ahora;
    enviarLote();
  }
  static uint32_t ultimoReporte = 0;
  if (ahora - ultimoReporte >= 60000) {   // estadisticas cada minuto
    ultimoReporte = ahora;
    Serial.printf("[Stats] envios ok=%lu fallidos=%lu confirmadas=%lu pendientes=%u descartadas=%lu\n",
                  (unsigned long)enviosOk, (unsigned long)enviosFallidos, (unsigned long)lecturasConfirmadas,
                  cantidad, (unsigned long)descartadasPorBufferLleno);
  }
  delay(10);
}
