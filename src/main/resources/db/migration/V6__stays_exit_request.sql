-- ============================================================
-- WBS-72 (dep. residente): el residente marca que una visita
-- debe retirarse. El guardia lo ve en la lista de presentes.
-- ============================================================
ALTER TABLE stays
    ADD COLUMN exit_requested_at TIMESTAMPTZ,
    ADD COLUMN exit_requested_by UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_stays_exit_requested
    ON stays (neighborhood_id, exit_requested_at)
    WHERE exited_at IS NULL AND exit_requested_at IS NOT NULL;
