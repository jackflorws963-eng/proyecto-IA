-- =====================================================================
-- SmartStock IoT - V1: esquema completo (PostgreSQL 16+)
-- Lo ejecuta Flyway automaticamente al iniciar el backend.
-- =====================================================================

-- ---------- Usuarios y seguridad ----------
CREATE TABLE rol (
    id      BIGSERIAL PRIMARY KEY,
    nombre  VARCHAR(30) NOT NULL UNIQUE
);

CREATE TABLE usuario (
    id               BIGSERIAL PRIMARY KEY,
    username         VARCHAR(50)  NOT NULL UNIQUE,
    password_hash    VARCHAR(100) NOT NULL,
    nombre_completo  VARCHAR(120) NOT NULL,
    activo           BOOLEAN      NOT NULL DEFAULT TRUE,
    rol_id           BIGINT       NOT NULL REFERENCES rol(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_usuario_username CHECK (length(trim(username)) >= 3)
);

-- ---------- Catalogo ----------
CREATE TABLE categoria (
    id           BIGSERIAL PRIMARY KEY,
    nombre       VARCHAR(80)  NOT NULL UNIQUE,
    descripcion  VARCHAR(255),
    activo       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE producto (
    id               BIGSERIAL PRIMARY KEY,
    codigo           VARCHAR(30)    NOT NULL UNIQUE,
    nombre           VARCHAR(120)   NOT NULL,
    descripcion      VARCHAR(255),
    categoria_id     BIGINT         NOT NULL REFERENCES categoria(id),
    unidad           VARCHAR(20)    NOT NULL,
    precio           NUMERIC(12,2)  NOT NULL,
    peso_unitario_g  NUMERIC(10,3),
    stock_minimo     INTEGER        NOT NULL DEFAULT 0,
    activo           BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_producto_precio  CHECK (precio >= 0),
    CONSTRAINT ck_producto_peso    CHECK (peso_unitario_g IS NULL OR peso_unitario_g > 0),
    CONSTRAINT ck_producto_stockmin CHECK (stock_minimo >= 0)
);
CREATE INDEX ix_producto_nombre    ON producto (lower(nombre));
CREATE INDEX ix_producto_categoria ON producto (categoria_id);

CREATE TABLE ubicacion (
    id           BIGSERIAL PRIMARY KEY,
    nombre       VARCHAR(80)   NOT NULL UNIQUE,
    descripcion  VARCHAR(255),
    temp_min     NUMERIC(5,2)  NOT NULL,
    temp_max     NUMERIC(5,2)  NOT NULL,
    hum_min      NUMERIC(5,2)  NOT NULL,
    hum_max      NUMERIC(5,2)  NOT NULL,
    activo       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_ubicacion_temp CHECK (temp_min < temp_max),
    CONSTRAINT ck_ubicacion_hum  CHECK (hum_min >= 0 AND hum_max <= 100 AND hum_min < hum_max)
);

-- ---------- Inventario ----------
-- Unica fuente de verdad de las existencias. Solo se modifica desde
-- MovimientoService dentro de una transaccion.
CREATE TABLE inventario (
    id            BIGSERIAL PRIMARY KEY,
    producto_id   BIGINT      NOT NULL REFERENCES producto(id),
    ubicacion_id  BIGINT      NOT NULL REFERENCES ubicacion(id),
    cantidad      INTEGER     NOT NULL DEFAULT 0,
    version       INTEGER     NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_inventario UNIQUE (producto_id, ubicacion_id),
    CONSTRAINT ck_inventario_no_negativo CHECK (cantidad >= 0)
);
CREATE INDEX ix_inventario_ubicacion ON inventario (ubicacion_id);

-- ---------- IoT ----------
CREATE TABLE dispositivo (
    id               BIGSERIAL PRIMARY KEY,
    codigo           VARCHAR(40)  NOT NULL UNIQUE,
    nombre           VARCHAR(80)  NOT NULL,
    api_key_hash     VARCHAR(100) NOT NULL,
    ubicacion_id     BIGINT       NOT NULL REFERENCES ubicacion(id),
    activo           BOOLEAN      NOT NULL DEFAULT TRUE,
    ultimo_contacto  TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE sensor (
    id              BIGSERIAL PRIMARY KEY,
    dispositivo_id  BIGINT      NOT NULL REFERENCES dispositivo(id),
    tipo            VARCHAR(20) NOT NULL,
    producto_id     BIGINT      REFERENCES producto(id),
    activo          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sensor_tipo CHECK (tipo IN ('TEMPERATURA','HUMEDAD','PESO')),
    CONSTRAINT ck_sensor_peso_producto CHECK (tipo <> 'PESO' OR producto_id IS NOT NULL),
    CONSTRAINT uq_sensor_dispositivo_tipo UNIQUE (dispositivo_id, tipo)
);

-- Medicion fisica: NUNCA modifica el inventario por si sola.
CREATE TABLE lectura (
    id           BIGSERIAL PRIMARY KEY,
    sensor_id    BIGINT        NOT NULL REFERENCES sensor(id),
    valor        NUMERIC(12,3) NOT NULL,
    seq          BIGINT        NOT NULL,
    medido_en    TIMESTAMPTZ   NOT NULL,
    recibido_en  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    estado       VARCHAR(20)   NOT NULL,
    observacion  VARCHAR(255),
    CONSTRAINT ck_lectura_estado CHECK (estado IN ('VALIDA','FUERA_RANGO')),
    CONSTRAINT uq_lectura_sensor_seq UNIQUE (sensor_id, seq)   -- anti-duplicados
);
CREATE INDEX ix_lectura_sensor_fecha ON lectura (sensor_id, medido_en DESC);

CREATE TABLE calibracion (
    id             BIGSERIAL PRIMARY KEY,
    sensor_id      BIGINT        NOT NULL REFERENCES sensor(id),
    factor         NUMERIC(14,6) NOT NULL,
    tara_g         NUMERIC(12,3) NOT NULL DEFAULT 0,
    peso_patron_g  NUMERIC(12,3),
    usuario_id     BIGINT        REFERENCES usuario(id),
    vigente        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_calibracion_factor CHECK (factor <> 0),
    CONSTRAINT ck_calibracion_tara   CHECK (tara_g >= 0)
);
-- Solo una calibracion vigente por sensor
CREATE UNIQUE INDEX uq_calibracion_vigente ON calibracion (sensor_id) WHERE vigente;

-- ---------- Inventario estimado por peso ----------
CREATE TABLE estimacion (
    id                   BIGSERIAL PRIMARY KEY,
    sensor_id            BIGINT        NOT NULL REFERENCES sensor(id),
    producto_id          BIGINT        NOT NULL REFERENCES producto(id),
    ubicacion_id         BIGINT        NOT NULL REFERENCES ubicacion(id),
    lectura_id           BIGINT        NOT NULL UNIQUE REFERENCES lectura(id),
    peso_neto_g          NUMERIC(12,3) NOT NULL,
    cantidad_estimada    NUMERIC(10,2) NOT NULL,
    cantidad_registrada  INTEGER       NOT NULL,
    diferencia_pct       NUMERIC(8,2)  NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_estimacion_sensor_fecha ON estimacion (sensor_id, created_at DESC);

-- Revision humana obligatoria antes de ajustar el stock
CREATE TABLE discrepancia (
    id             BIGSERIAL PRIMARY KEY,
    estimacion_id  BIGINT       NOT NULL REFERENCES estimacion(id),
    sensor_id      BIGINT       NOT NULL REFERENCES sensor(id),
    estado         VARCHAR(20)  NOT NULL DEFAULT 'PENDIENTE',
    revisado_por   BIGINT       REFERENCES usuario(id),
    revisado_en    TIMESTAMPTZ,
    comentario     VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_discrepancia_estado CHECK (estado IN ('PENDIENTE','APROBADA','RECHAZADA'))
);
-- Como maximo una discrepancia pendiente por sensor
CREATE UNIQUE INDEX uq_discrepancia_pendiente ON discrepancia (sensor_id) WHERE estado = 'PENDIENTE';

-- ---------- Movimientos (hecho comercial) ----------
CREATE TABLE movimiento (
    id                    BIGSERIAL PRIMARY KEY,
    tipo                  VARCHAR(20)  NOT NULL,
    producto_id           BIGINT       NOT NULL REFERENCES producto(id),
    ubicacion_origen_id   BIGINT       REFERENCES ubicacion(id),
    ubicacion_destino_id  BIGINT       REFERENCES ubicacion(id),
    cantidad              INTEGER      NOT NULL,
    motivo                VARCHAR(255) NOT NULL,
    usuario_id            BIGINT       NOT NULL REFERENCES usuario(id),
    discrepancia_id       BIGINT       REFERENCES discrepancia(id),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_movimiento_tipo CHECK (tipo IN
        ('ENTRADA','SALIDA','AJUSTE_POSITIVO','AJUSTE_NEGATIVO','TRANSFERENCIA')),
    CONSTRAINT ck_movimiento_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_movimiento_motivo CHECK (length(trim(motivo)) > 0),
    CONSTRAINT ck_movimiento_ubicaciones CHECK (
        (tipo IN ('ENTRADA','AJUSTE_POSITIVO') AND ubicacion_destino_id IS NOT NULL AND ubicacion_origen_id IS NULL) OR
        (tipo IN ('SALIDA','AJUSTE_NEGATIVO')  AND ubicacion_origen_id  IS NOT NULL AND ubicacion_destino_id IS NULL) OR
        (tipo = 'TRANSFERENCIA' AND ubicacion_origen_id IS NOT NULL AND ubicacion_destino_id IS NOT NULL
             AND ubicacion_origen_id <> ubicacion_destino_id)
    )
);
CREATE INDEX ix_movimiento_producto ON movimiento (producto_id, created_at DESC);
CREATE INDEX ix_movimiento_fecha    ON movimiento (created_at DESC);

-- ---------- Alertas ----------
CREATE TABLE alerta (
    id              BIGSERIAL PRIMARY KEY,
    tipo            VARCHAR(30)  NOT NULL,
    prioridad       VARCHAR(10)  NOT NULL,
    estado          VARCHAR(15)  NOT NULL DEFAULT 'ABIERTA',
    clave_dedup     VARCHAR(100) NOT NULL,
    mensaje         VARCHAR(255) NOT NULL,
    ocurrencias     INTEGER      NOT NULL DEFAULT 1,
    primera_vez     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ultima_vez      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    reconocida_por  BIGINT       REFERENCES usuario(id),
    reconocida_en   TIMESTAMPTZ,
    cerrada_en      TIMESTAMPTZ,
    CONSTRAINT ck_alerta_tipo CHECK (tipo IN
        ('STOCK_MINIMO','TEMPERATURA','HUMEDAD','DISCREPANCIA','DISPOSITIVO_OFFLINE')),
    CONSTRAINT ck_alerta_prioridad CHECK (prioridad IN ('BAJA','MEDIA','ALTA')),
    CONSTRAINT ck_alerta_estado CHECK (estado IN ('ABIERTA','RECONOCIDA','CERRADA'))
);
-- Evita alertas repetidas: una sola alerta no cerrada por clave
CREATE UNIQUE INDEX uq_alerta_activa ON alerta (clave_dedup) WHERE estado <> 'CERRADA';
CREATE INDEX ix_alerta_estado ON alerta (estado, ultima_vez DESC);

-- ---------- Auditoria ----------
CREATE TABLE auditoria (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(50),
    accion      VARCHAR(40)  NOT NULL,
    entidad     VARCHAR(40)  NOT NULL,
    entidad_id  BIGINT,
    detalle     TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_auditoria_entidad ON auditoria (entidad, entidad_id);
CREATE INDEX ix_auditoria_fecha   ON auditoria (created_at DESC);

-- ---------- Proteccion de registros historicos ----------
-- Lecturas, movimientos y auditoria no se editan ni se borran:
-- los errores se corrigen con registros compensatorios.
CREATE OR REPLACE FUNCTION fn_bloquear_modificacion() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'La tabla % es historica: no se permite %', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_auditoria_inmutable  BEFORE UPDATE OR DELETE ON auditoria
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER tr_movimiento_inmutable BEFORE UPDATE OR DELETE ON movimiento
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
CREATE TRIGGER tr_lectura_inmutable    BEFORE UPDATE OR DELETE ON lectura
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();
