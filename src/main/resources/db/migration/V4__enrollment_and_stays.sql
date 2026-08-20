-- ============================================================
-- D-08: el DNI del invitado pasa a obligatorio
-- ============================================================
UPDATE invitations SET guest_document = 'PENDIENTE'
    WHERE guest_document IS NULL;

ALTER TABLE invitations
    ALTER COLUMN guest_document SET NOT NULL;

ALTER TABLE invitations
    ADD CONSTRAINT chk_guest_document
    CHECK (guest_document ~ '^[0-9]{7,8}$' OR guest_document = 'PENDIENTE');

-- ============================================================
-- Politica de extension por barrio (US-43)
-- ============================================================
ALTER TABLE neighborhoods
    ADD COLUMN max_extensions        SMALLINT NOT NULL DEFAULT 2,
    ADD COLUMN extension_minutes     INT      NOT NULL DEFAULT 120,
    ADD COLUMN stay_warning_minutes  INT      NOT NULL DEFAULT 20;

ALTER TABLE invitations
    ADD COLUMN extension_count SMALLINT NOT NULL DEFAULT 0;

-- ============================================================
-- Enrolamiento: un invitado se verifica una vez por invitacion
-- ============================================================
CREATE TABLE guest_enrollments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invitation_id   UUID NOT NULL REFERENCES invitations(id) ON DELETE CASCADE,
    document_number VARCHAR(20) NOT NULL,
    enrolled_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    enrolled_by     UUID REFERENCES users(id) ON DELETE SET NULL,
    gate_id         UUID REFERENCES gates(id) ON DELETE SET NULL,
    UNIQUE (invitation_id)
);
CREATE INDEX idx_enrollments_document ON guest_enrollments (document_number);

-- ============================================================
-- Estadias: par ingreso/egreso, para saber quien esta adentro
-- ============================================================
CREATE TABLE stays (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id   UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    invitation_id     UUID REFERENCES invitations(id) ON DELETE SET NULL,
    lot_id            UUID REFERENCES lots(id) ON DELETE SET NULL,

    subject_name      VARCHAR(140) NOT NULL,
    subject_document  VARCHAR(20),
    companions        SMALLINT NOT NULL DEFAULT 0,

    entry_log_id      UUID NOT NULL REFERENCES access_logs(id) ON DELETE RESTRICT,
    exit_log_id       UUID REFERENCES access_logs(id) ON DELETE SET NULL,
    entered_at        TIMESTAMPTZ NOT NULL,
    exited_at         TIMESTAMPTZ,
    expected_until    TIMESTAMPTZ NOT NULL,

    closed_manually   BOOLEAN NOT NULL DEFAULT FALSE,
    closed_by         UUID REFERENCES users(id) ON DELETE SET NULL,

    warned_at         TIMESTAMPTZ,
    resident_notified_at TIMESTAMPTZ,

    CONSTRAINT chk_stay_window CHECK (exited_at IS NULL OR exited_at >= entered_at)
);

CREATE UNIQUE INDEX uq_open_stay_per_invitation
    ON stays (invitation_id) WHERE exited_at IS NULL AND invitation_id IS NOT NULL;

CREATE INDEX idx_stays_open
    ON stays (neighborhood_id, expected_until)
    WHERE exited_at IS NULL;

-- ============================================================
-- Nuevo motivo de denegacion: DOCUMENT_MISMATCH
-- ============================================================
ALTER TABLE access_logs DROP CONSTRAINT IF EXISTS access_logs_denial_reason_check;
ALTER TABLE access_logs
    ADD CONSTRAINT access_logs_denial_reason_check
    CHECK (denial_reason IN (
        'INVALID_SIGNATURE','NOT_YET_VALID','EXPIRED',
        'ALREADY_USED','REVOKED','OUT_OF_SCHEDULE',
        'LOT_SUSPENDED','REPLAY','DOCUMENT_MISMATCH','UNKNOWN'));
