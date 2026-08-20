-- ============================================================
-- Pase personal del residente (US-16, BR-10)
-- ============================================================
ALTER TABLE invitations
    ADD COLUMN resident_pass BOOLEAN NOT NULL DEFAULT FALSE;

-- Un residente activa un solo pase permanente por lote.
CREATE UNIQUE INDEX uq_invitations_resident_pass
    ON invitations (created_by, lot_id)
    WHERE resident_pass = TRUE AND status = 'ACTIVE';
