-- #195: formato de cada export. Las filas existentes son todas CSV (el XLSX
-- no existia). ADD COLUMN con default no reescribe filas ni dispara el
-- trigger append-only de V19.
ALTER TABLE access_log_exports
    ADD COLUMN format VARCHAR(10) NOT NULL DEFAULT 'CSV'
        CONSTRAINT chk_export_format CHECK (format IN ('CSV', 'XLSX'));

-- Sin default desde la app: un llamador nuevo que no pase el formato falla
-- en vez de quedar auditado como CSV.
ALTER TABLE access_log_exports ALTER COLUMN format DROP DEFAULT;
