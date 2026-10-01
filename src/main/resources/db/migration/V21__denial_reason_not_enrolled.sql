-- ============================================================
-- Nuevos motivos de denegacion
-- NOT_ENROLLED: ingreso sin enrolamiento en el barrio. Lo produce el
--   sync (la garita lo dejo pasar; el server lo audita como denegado) y
--   la garita cuando el guardia cancela el paso del DNI.
-- WORKER_NOT_SYNCED, NEIGHBORHOOD_TZ_MISSING: solo los produce la garita
--   offline. El dispositivo no tenia los datos: senal de garita
--   desincronizada para el admin.
-- ============================================================
ALTER TABLE access_logs DROP CONSTRAINT IF EXISTS access_logs_denial_reason_check;
ALTER TABLE access_logs
    ADD CONSTRAINT access_logs_denial_reason_check
    CHECK (denial_reason IN (
        'INVALID_SIGNATURE','NOT_YET_VALID','EXPIRED',
        'ALREADY_USED','REVOKED','OUT_OF_SCHEDULE',
        'LOT_SUSPENDED','REPLAY','DOCUMENT_MISMATCH','NOT_ENROLLED',
        'WORKER_NOT_SYNCED','NEIGHBORHOOD_TZ_MISSING','UNKNOWN'));
