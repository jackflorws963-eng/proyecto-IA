-- =====================================================================
-- SmartStock IoT - V5: ventas
-- Cada linea de venta genera un movimiento SALIDA (el stock solo cambia
-- por movimientos). Los precios incluyen IGV. Una venta no se borra:
-- se ANULA y el stock vuelve con un movimiento ENTRADA.
-- =====================================================================

-- Numeracion correlativa por tipo de comprobante (se bloquea la fila al emitir)
CREATE TABLE serie_comprobante (
    tipo    VARCHAR(15) PRIMARY KEY,
    serie   VARCHAR(4)  NOT NULL UNIQUE,
    ultimo  INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT ck_serie_tipo   CHECK (tipo IN ('BOLETA', 'FACTURA', 'NOTA_VENTA')),
    CONSTRAINT ck_serie_ultimo CHECK (ultimo >= 0)
);
INSERT INTO serie_comprobante (tipo, serie) VALUES
  ('BOLETA', 'B001'), ('FACTURA', 'F001'), ('NOTA_VENTA', 'NV01');

CREATE TABLE venta (
    id                 BIGSERIAL PRIMARY KEY,
    tipo_comprobante   VARCHAR(15)   NOT NULL,
    serie              VARCHAR(4)    NOT NULL,
    correlativo        INTEGER       NOT NULL,
    ubicacion_id       BIGINT        NOT NULL REFERENCES ubicacion(id),
    cliente_documento  VARCHAR(11),
    cliente_nombre     VARCHAR(120),
    metodo_pago        VARCHAR(15)   NOT NULL,
    subtotal           NUMERIC(12,2) NOT NULL,
    igv                NUMERIC(12,2) NOT NULL,
    total              NUMERIC(12,2) NOT NULL,
    monto_recibido     NUMERIC(12,2),
    vuelto             NUMERIC(12,2),
    estado             VARCHAR(10)   NOT NULL DEFAULT 'EMITIDA',
    usuario_id         BIGINT        NOT NULL REFERENCES usuario(id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    anulada_por        BIGINT        REFERENCES usuario(id),
    anulada_en         TIMESTAMPTZ,
    motivo_anulacion   VARCHAR(255),
    CONSTRAINT uq_venta_numero      UNIQUE (serie, correlativo),
    CONSTRAINT ck_venta_tipo        CHECK (tipo_comprobante IN ('BOLETA', 'FACTURA', 'NOTA_VENTA')),
    CONSTRAINT ck_venta_pago        CHECK (metodo_pago IN ('EFECTIVO', 'TARJETA', 'YAPE', 'PLIN', 'TRANSFERENCIA')),
    CONSTRAINT ck_venta_estado      CHECK (estado IN ('EMITIDA', 'ANULADA')),
    CONSTRAINT ck_venta_montos      CHECK (total > 0 AND subtotal >= 0 AND igv >= 0 AND subtotal + igv = total),
    CONSTRAINT ck_venta_efectivo    CHECK (metodo_pago <> 'EFECTIVO' OR (monto_recibido >= total AND vuelto = monto_recibido - total)),
    -- Factura: RUC de 11 digitos y razon social obligatorios
    CONSTRAINT ck_venta_factura     CHECK (tipo_comprobante <> 'FACTURA'
        OR (cliente_documento ~ '^\d{11}$' AND length(trim(cliente_nombre)) > 0)),
    CONSTRAINT ck_venta_documento   CHECK (cliente_documento IS NULL OR cliente_documento ~ '^(\d{8}|\d{11})$'),
    CONSTRAINT ck_venta_anulacion   CHECK ((estado = 'ANULADA') = (anulada_en IS NOT NULL AND anulada_por IS NOT NULL
        AND length(trim(coalesce(motivo_anulacion, ''))) > 0))
);
CREATE INDEX ix_venta_fecha ON venta (created_at DESC);

CREATE TABLE venta_detalle (
    id               BIGSERIAL PRIMARY KEY,
    venta_id         BIGINT        NOT NULL REFERENCES venta(id),
    producto_id      BIGINT        NOT NULL REFERENCES producto(id),
    cantidad         INTEGER       NOT NULL,
    precio_unitario  NUMERIC(12,2) NOT NULL,
    importe          NUMERIC(12,2) NOT NULL,
    movimiento_id    BIGINT        NOT NULL UNIQUE REFERENCES movimiento(id),
    CONSTRAINT ck_detalle_cantidad CHECK (cantidad > 0),
    CONSTRAINT ck_detalle_precio   CHECK (precio_unitario >= 0),
    CONSTRAINT ck_detalle_importe  CHECK (importe = cantidad * precio_unitario)
);
CREATE INDEX ix_venta_detalle_venta ON venta_detalle (venta_id);
CREATE INDEX ix_venta_detalle_producto ON venta_detalle (producto_id);

-- El detalle es historico; de la venta solo se permite pasar de EMITIDA a ANULADA
CREATE TRIGGER tr_venta_detalle_inmutable BEFORE UPDATE OR DELETE ON venta_detalle
    FOR EACH ROW EXECUTE FUNCTION fn_bloquear_modificacion();

CREATE OR REPLACE FUNCTION fn_venta_solo_anular() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Las ventas no se borran: se anulan';
    END IF;
    IF OLD.estado <> 'EMITIDA' OR NEW.estado <> 'ANULADA'
       OR (NEW.tipo_comprobante, NEW.serie, NEW.correlativo, NEW.ubicacion_id, NEW.cliente_documento,
           NEW.cliente_nombre, NEW.metodo_pago, NEW.subtotal, NEW.igv, NEW.total, NEW.monto_recibido,
           NEW.vuelto, NEW.usuario_id, NEW.created_at)
          IS DISTINCT FROM
          (OLD.tipo_comprobante, OLD.serie, OLD.correlativo, OLD.ubicacion_id, OLD.cliente_documento,
           OLD.cliente_nombre, OLD.metodo_pago, OLD.subtotal, OLD.igv, OLD.total, OLD.monto_recibido,
           OLD.vuelto, OLD.usuario_id, OLD.created_at) THEN
        RAISE EXCEPTION 'Una venta emitida solo puede anularse';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_venta_solo_anular BEFORE UPDATE OR DELETE ON venta
    FOR EACH ROW EXECUTE FUNCTION fn_venta_solo_anular();
