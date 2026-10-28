/*
 * SmartStock IoT - Calibracion de la celda de carga (HX711)
 * ------------------------------------------------------------------
 * Obtiene el factor de escala que convierte la lectura cruda del HX711 a gramos.
 *
 * Procedimiento:
 *  1. Cargue este sketch con la balanza VACIA (sin nada encima).
 *  2. Abra el Monitor Serie a 115200 baudios.
 *  3. Cuando se indique, coloque un peso patron conocido (ej. 500 g)
 *     y escriba su peso en gramos en el Monitor Serie + Enter.
 *  4. Copie el factor mostrado en HX711_CALIBRATION_FACTOR de secrets.h
 *     y registrelo en la web (Dispositivos IoT > Calibracion).
 *  5. Verifique con otros pesos conocidos: el sketch muestra el error %.
 *
 * Conexiones: DT->GPIO16, SCK->GPIO17 (igual que el firmware principal).
 */
#include <HX711.h>

const uint8_t PIN_HX_DT = 16;
const uint8_t PIN_HX_SCK = 17;
HX711 balanza;
float factor = 0;
float pesoPatron = 0;

float leerNumero() {
  while (!Serial.available()) delay(50);
  float v = Serial.parseFloat();
  while (Serial.available()) Serial.read();   // limpiar el resto de la linea
  return v;
}

void setup() {
  Serial.begin(115200);
  delay(500);
  balanza.begin(PIN_HX_DT, PIN_HX_SCK);
  if (!balanza.wait_ready_timeout(3000)) {
    Serial.println("HX711 no detectado. Revise conexiones.");
    while (true) delay(1000);
  }
  Serial.println("Ajustando cero con la balanza VACIA...");
  balanza.set_scale();       // escala 1: lecturas crudas
  balanza.tare(30);
  Serial.println("Coloque el peso patron y escriba su peso en gramos:");
  pesoPatron = leerNumero();
  if (pesoPatron <= 0) {
    Serial.println("Peso invalido. Reinicie el ESP32.");
    while (true) delay(1000);
  }
  long crudo = balanza.get_value(30);   // promedio de 30 lecturas sin tara
  factor = (float)crudo / pesoPatron;
  balanza.set_scale(factor);
  Serial.printf("\nLectura cruda promedio: %ld\n", crudo);
  Serial.printf(">>> FACTOR DE CALIBRACION: %.6f <<<\n", factor);
  Serial.println("Copie este valor en HX711_CALIBRATION_FACTOR (secrets.h).");
  Serial.println("\nVerificacion: coloque otros pesos conocidos. Escriba el peso real para ver el error %.");
}

void loop() {
  if (Serial.available()) {
    float real = leerNumero();
    float medido = balanza.get_units(20);
    float error = real != 0 ? (medido - real) / real * 100.0f : 0;
    Serial.printf("Real %.2f g | Medido %.2f g | Error %.2f %%\n", real, medido, error);
  }
  static uint32_t t = 0;
  if (millis() - t > 2000) {
    t = millis();
    Serial.printf("Peso actual: %.2f g\n", balanza.get_units(10));
  }
}
