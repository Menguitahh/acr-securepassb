-- ============================================================
-- Nuevo motivo de denegacion: NOT_ENROLLED
-- Ingreso sincronizado de alguien sin enrolamiento en el barrio.
-- La garita lo dejo pasar; el server lo audita como denegado.
-- ============================================================
ALTER TABLE access_logs DROP CONSTRAINT IF EXISTS access_logs_denial_reason_check;
ALTER TABLE access_logs
    ADD CONSTRAINT access_logs_denial_reason_check
    CHECK (denial_reason IN (
        'INVALID_SIGNATURE','NOT_YET_VALID','EXPIRED',
        'ALREADY_USED','REVOKED','OUT_OF_SCHEDULE',
        'LOT_SUSPENDED','REPLAY','DOCUMENT_MISMATCH','NOT_ENROLLED','UNKNOWN'));
