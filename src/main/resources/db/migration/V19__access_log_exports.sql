-- US-32: auditoria de exports de la bitacora. Append-only como access_logs.
CREATE TABLE access_log_exports (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE RESTRICT,
    actor_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    range_from      TIMESTAMPTZ NOT NULL,
    range_to        TIMESTAMPTZ NOT NULL,
    -- Sin FK: se audita el filtro tal como se pidio, aunque el lote cambie despues.
    lot_id          UUID,
    result          VARCHAR(20) CHECK (result IN ('ALLOWED','DENIED')),
    exported_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_export_range CHECK (range_to > range_from)
);
CREATE INDEX idx_log_exports_neighborhood_time ON access_log_exports (neighborhood_id, exported_at DESC);

-- La funcion de V1 nombraba access_logs fijo; ahora sirve para las dos tablas.
CREATE OR REPLACE FUNCTION deny_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% es append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_access_log_exports_immutable
    BEFORE UPDATE OR DELETE ON access_log_exports
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();
