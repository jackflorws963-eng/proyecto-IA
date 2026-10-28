-- =====================================================================
-- SmartStock IoT - V2: datos iniciales de demostracion
-- Los usuarios y la clave del dispositivo NO van aqui: los crea
-- DataInitializer.java con valores leidos de variables de entorno,
-- para no guardar contrasenas ni secretos en el repositorio.
-- =====================================================================

INSERT INTO rol (nombre) VALUES ('ADMIN'), ('OPERADOR');

INSERT INTO categoria (nombre, descripcion) VALUES
  ('Ferreteria',  'Tornillos, pernos y accesorios'),
  ('Alimentos',   'Productos perecibles que requieren control ambiental'),
  ('Electronica', 'Componentes electronicos');

INSERT INTO ubicacion (nombre, descripcion, temp_min, temp_max, hum_min, hum_max) VALUES
  ('Almacen A', 'Estante con celda de carga (demo IoT)', 5.00, 28.00, 20.00, 70.00),
  ('Almacen B', 'Almacen secundario',                    5.00, 30.00, 20.00, 75.00);

INSERT INTO producto (codigo, nombre, descripcion, categoria_id, unidad, precio, peso_unitario_g, stock_minimo) VALUES
  ('P001', 'Pernos M8 (bolsa)',      'Bolsa de pernos M8 de 50 g',     1, 'bolsa',  3.50,  50.000, 5),
  ('P002', 'Arroz 1 kg',             'Bolsa de arroz superior 1 kg',   2, 'bolsa',  4.80, 1000.000, 10),
  ('P003', 'Modulo ESP32 DevKit',    'Placa de desarrollo ESP32',      3, 'unidad', 32.00, 10.000, 3),
  ('P004', 'Cable USB tipo C',       'Cable de 1 m',                   3, 'unidad', 8.00,  NULL,   4);

-- Dispositivo de la demo. 'PENDIENTE' se reemplaza al iniciar por el hash
-- BCrypt de la variable de entorno DEVICE_KEY (ver DataInitializer).
INSERT INTO dispositivo (codigo, nombre, api_key_hash, ubicacion_id) VALUES
  ('ESP32-01', 'Nodo estante A', 'PENDIENTE', 1);

INSERT INTO sensor (dispositivo_id, tipo, producto_id) VALUES
  (1, 'TEMPERATURA', NULL),
  (1, 'HUMEDAD',     NULL),
  (1, 'PESO',        1);

-- Calibracion inicial: factor 1 y sin tara (se recalibra desde la web)
INSERT INTO calibracion (sensor_id, factor, tara_g, peso_patron_g, usuario_id, vigente) VALUES
  (3, 1.000000, 0.000, NULL, NULL, TRUE);
