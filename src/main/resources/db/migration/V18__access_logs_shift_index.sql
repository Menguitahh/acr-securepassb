-- US-33: GET /shifts cuenta accesos por turno. Parcial: la mayoria de los
-- logs sin turno (shift_id NULL) no aportan al conteo.
CREATE INDEX idx_logs_shift ON access_logs (shift_id) WHERE shift_id IS NOT NULL;
