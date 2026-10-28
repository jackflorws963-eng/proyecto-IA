-- =====================================================================
-- SmartStock IoT - V3: indices para consultas frecuentes
-- (V1 y V2 no se modifican: Flyway valida su checksum en las bases ya creadas)
-- =====================================================================

-- El dashboard cuenta cada 5 s las lecturas recibidas en la ultima hora
-- (LecturaRepository.contarDesde). Sin indice recorria toda la tabla.
CREATE INDEX ix_lectura_recibido ON lectura (recibido_en);

-- La aprobacion y la confirmacion de discrepancias buscan las ultimas estimaciones por sensor (orden por id).
CREATE INDEX ix_estimacion_sensor_id ON estimacion (sensor_id, id DESC);
