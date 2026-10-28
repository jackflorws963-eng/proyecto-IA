-- =====================================================================
-- SmartStock IoT - V6: memoria y aprendizaje del asistente de IA local
-- Autor: Brayan (BR)
-- =====================================================================

-- Lo que el asistente aprende de las personas: hechos ("el proveedor del arroz es X")
-- y preferencias ("respondeme corto"). Se recuperan por similitud con cada pregunta.
CREATE TABLE ia_memoria (
    id          BIGSERIAL PRIMARY KEY,
    tipo        VARCHAR(12)  NOT NULL,
    texto       VARCHAR(500) NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    usos        INTEGER      NOT NULL DEFAULT 0,
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    usado_en    TIMESTAMPTZ,
    CONSTRAINT ck_ia_memoria_tipo CHECK (tipo IN ('HECHO', 'PREFERENCIA')),
    CONSTRAINT ck_ia_memoria_texto CHECK (length(trim(texto)) > 0)
);
COMMENT ON TABLE ia_memoria IS 'Conocimiento que el asistente aprende de los usuarios';

-- Frases etiquetadas con la intencion correcta. Entrenan en linea el clasificador local:
-- origen USUARIO = la persona corrigio o enseño; MODELO = lo dedujo el modelo de lenguaje local.
CREATE TABLE ia_ejemplo (
    id          BIGSERIAL PRIMARY KEY,
    texto       VARCHAR(500) NOT NULL,
    intencion   VARCHAR(40)  NOT NULL,
    origen      VARCHAR(10)  NOT NULL,
    username    VARCHAR(50)  NOT NULL,
    creado_en   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ia_ejemplo_origen CHECK (origen IN ('USUARIO', 'MODELO')),
    CONSTRAINT uq_ia_ejemplo UNIQUE (texto, intencion)
);

-- Pronostico de demanda: salidas diarias por producto
CREATE INDEX ix_movimiento_tipo_fecha ON movimiento (tipo, created_at);
