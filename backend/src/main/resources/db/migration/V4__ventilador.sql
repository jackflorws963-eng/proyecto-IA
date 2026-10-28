-- =====================================================================
-- SmartStock IoT - V4: ventilador (rele en el ESP32)
-- El servidor decide si el ventilador debe estar encendido y se lo indica al
-- ESP32 en la respuesta de cada lote de lecturas (campo "ventilador").
--   AUTO      -> se enciende si la temperatura supera temp_max de la ubicacion
--   ENCENDIDO -> encendido manual (usuario o asistente)
--   APAGADO   -> apagado manual
-- =====================================================================
ALTER TABLE dispositivo
    ADD COLUMN ventilador_modo      VARCHAR(10) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN ventilador_encendido BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN ventilador_cambio    TIMESTAMPTZ,
    ADD CONSTRAINT ck_dispositivo_ventilador CHECK (ventilador_modo IN ('AUTO', 'ENCENDIDO', 'APAGADO'));
