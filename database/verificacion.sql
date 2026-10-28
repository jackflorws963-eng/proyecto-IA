SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank;

SELECT u.username, r.nombre AS rol, u.activo, left(u.password_hash, 7) AS hash_prefijo
FROM usuario u JOIN rol r ON r.id = u.rol_id;

WITH neto AS (
    SELECT producto_id, ubicacion_destino_id AS ubicacion_id, SUM(cantidad) AS delta
    FROM movimiento WHERE ubicacion_destino_id IS NOT NULL GROUP BY 1, 2
    UNION ALL
    SELECT producto_id, ubicacion_origen_id, -SUM(cantidad)
    FROM movimiento WHERE ubicacion_origen_id IS NOT NULL GROUP BY 1, 2
), esperado AS (
    SELECT producto_id, ubicacion_id, SUM(delta) AS cantidad FROM neto GROUP BY 1, 2
)
SELECT i.producto_id, i.ubicacion_id, i.cantidad AS en_inventario, COALESCE(e.cantidad, 0) AS segun_movimientos
FROM inventario i
LEFT JOIN esperado e ON e.producto_id = i.producto_id AND e.ubicacion_id = i.ubicacion_id
WHERE i.cantidad <> COALESCE(e.cantidad, 0);

SELECT COUNT(*) AS filas_negativas FROM inventario WHERE cantidad < 0;

SELECT p.codigo, p.nombre, u.nombre AS ubicacion, i.cantidad, p.stock_minimo,
       CASE WHEN i.cantidad < p.stock_minimo THEN 'BAJO MINIMO' ELSE 'OK' END AS estado
FROM inventario i JOIN producto p ON p.id = i.producto_id JOIN ubicacion u ON u.id = i.ubicacion_id
ORDER BY p.codigo;

SELECT l.id, d.codigo AS dispositivo, s.tipo, l.valor, l.seq, l.medido_en, l.recibido_en, l.estado
FROM lectura l JOIN sensor s ON s.id = l.sensor_id JOIN dispositivo d ON d.id = s.dispositivo_id
ORDER BY l.recibido_en DESC LIMIT 20;

SELECT sensor_id, seq, COUNT(*) FROM lectura GROUP BY 1, 2 HAVING COUNT(*) > 1;

SELECT s.tipo,
       ROUND(AVG(EXTRACT(EPOCH FROM (l.recibido_en - l.medido_en)))::numeric, 2) AS latencia_prom_s,
       ROUND(MAX(EXTRACT(EPOCH FROM (l.recibido_en - l.medido_en)))::numeric, 2) AS latencia_max_s,
       COUNT(*) AS n
FROM lectura l JOIN sensor s ON s.id = l.sensor_id
WHERE l.recibido_en > l.medido_en
GROUP BY s.tipo;

SELECT sensor_id,
       MAX(seq) - MIN(seq) + 1 AS esperadas,
       COUNT(*) AS recibidas,
       ROUND(100.0 * COUNT(*) / NULLIF(MAX(seq) - MIN(seq) + 1, 0), 2) AS pct_recibidas
FROM lectura GROUP BY sensor_id;

SELECT p.codigo,
       COUNT(*) AS estimaciones,
       ROUND(AVG(e.diferencia_pct), 2) AS error_prom_pct,
       ROUND(MAX(e.diferencia_pct), 2) AS error_max_pct
FROM estimacion e JOIN producto p ON p.id = e.producto_id
GROUP BY p.codigo;

SELECT d.id, d.estado, p.codigo, e.cantidad_estimada, e.cantidad_registrada, e.diferencia_pct,
       u.username AS revisado_por, d.comentario, d.created_at
FROM discrepancia d JOIN estimacion e ON e.id = d.estimacion_id
JOIN producto p ON p.id = e.producto_id LEFT JOIN usuario u ON u.id = d.revisado_por
ORDER BY d.created_at DESC;

SELECT tipo, estado, COUNT(*), SUM(ocurrencias) AS ocurrencias FROM alerta GROUP BY 1, 2 ORDER BY 1, 2;
SELECT clave_dedup, COUNT(*) FROM alerta WHERE estado <> 'CERRADA' GROUP BY 1 HAVING COUNT(*) > 1;

SELECT clave_dedup, primera_vez, cerrada_en,
       ROUND(EXTRACT(EPOCH FROM (cerrada_en - primera_vez))::numeric, 0) AS segundos_hasta_detectar_reconexion
FROM alerta WHERE tipo = 'DISPOSITIVO_OFFLINE' AND cerrada_en IS NOT NULL ORDER BY primera_vez DESC;

SELECT created_at, username, accion, entidad, entidad_id, detalle FROM auditoria ORDER BY id DESC LIMIT 30;
