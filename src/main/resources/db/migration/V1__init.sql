CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ============================================================
-- BARRIOS Y ACCESOS FISICOS
-- ============================================================
CREATE TABLE neighborhoods (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(120)  NOT NULL,
    slug            VARCHAR(60)   NOT NULL UNIQUE,
    timezone        VARCHAR(60)   NOT NULL DEFAULT 'America/Argentina/Mendoza',
    stay_alert_minutes INT        NOT NULL DEFAULT 480,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE gates (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    name            VARCHAR(80) NOT NULL,
    direction       VARCHAR(10) NOT NULL CHECK (direction IN ('IN','OUT','BOTH')),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (neighborhood_id, name)
);

CREATE TABLE signing_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    kid             VARCHAR(16) NOT NULL,
    public_key      TEXT        NOT NULL,
    private_key_enc TEXT        NOT NULL,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    rotated_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (neighborhood_id, kid)
);
CREATE UNIQUE INDEX uq_signing_keys_active
    ON signing_keys (neighborhood_id) WHERE active;

-- ============================================================
-- PADRON CATASTRAL
-- ============================================================
CREATE TABLE lots (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    code            VARCHAR(30) NOT NULL,
    street          VARCHAR(120),
    number          VARCHAR(20),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','SUSPENDED','VACANT')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (neighborhood_id, code)
);

-- ============================================================
-- IDENTIDAD
-- ============================================================
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(160) UNIQUE,
    phone           VARCHAR(30)  UNIQUE,
    password_hash   VARCHAR(120) NOT NULL,
    full_name       VARCHAR(140) NOT NULL,
    document_number VARCHAR(20),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','SUSPENDED','DELETED')),
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_contact CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

CREATE TABLE memberships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL
                    CHECK (role IN ('ADMIN','GUARD','RESIDENT_OWNER','RESIDENT_MEMBER')),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, neighborhood_id, role)
);
CREATE INDEX idx_memberships_user ON memberships (user_id) WHERE active;

CREATE TABLE family_members (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lot_id          UUID NOT NULL REFERENCES lots(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    relation        VARCHAR(30),
    is_owner        BOOLEAN NOT NULL DEFAULT FALSE,
    can_invite      BOOLEAN NOT NULL DEFAULT TRUE,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (lot_id, user_id)
);
CREATE INDEX idx_family_members_lot ON family_members (lot_id) WHERE active;

-- ============================================================
-- INVITACIONES
-- ============================================================
CREATE TABLE invitations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    lot_id          UUID NOT NULL REFERENCES lots(id) ON DELETE RESTRICT,
    created_by      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

    type            VARCHAR(20) NOT NULL
                    CHECK (type IN ('SINGLE','EVENT','RECURRING')),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE','USED','EXPIRED','REVOKED')),

    guest_name      VARCHAR(140) NOT NULL,
    guest_document  VARCHAR(20),
    companions      SMALLINT NOT NULL DEFAULT 0 CHECK (companions BETWEEN 0 AND 20),
    notes           VARCHAR(280),

    valid_from      TIMESTAMPTZ NOT NULL,
    valid_until     TIMESTAMPTZ NOT NULL,
    weekdays        SMALLINT[],
    daily_from      TIME,
    daily_until     TIME,

    max_uses        INT NOT NULL DEFAULT 1 CHECK (max_uses > 0),
    used_count      INT NOT NULL DEFAULT 0 CHECK (used_count >= 0),

    token_kid       VARCHAR(16) NOT NULL,
    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_window CHECK (valid_until > valid_from),
    CONSTRAINT chk_uses   CHECK (used_count <= max_uses),
    CONSTRAINT chk_single CHECK (type <> 'SINGLE' OR max_uses = 1),
    CONSTRAINT chk_recurring_slot CHECK (
        type <> 'RECURRING' OR (weekdays IS NOT NULL AND daily_from IS NOT NULL AND daily_until IS NOT NULL)
    )
);
CREATE INDEX idx_invitations_lot     ON invitations (lot_id, created_at DESC);
CREATE INDEX idx_invitations_active  ON invitations (neighborhood_id, status)
    WHERE status = 'ACTIVE';

-- ============================================================
-- TURNOS DE GUARDIA
-- ============================================================
CREATE TABLE guard_shifts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    gate_id         UUID NOT NULL REFERENCES gates(id) ON DELETE RESTRICT,
    guard_id        UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ,
    handover_notes  TEXT,
    CONSTRAINT chk_shift_window CHECK (ended_at IS NULL OR ended_at > started_at)
);
CREATE UNIQUE INDEX uq_guard_open_shift
    ON guard_shifts (guard_id) WHERE ended_at IS NULL;
CREATE INDEX idx_shifts_gate ON guard_shifts (gate_id, started_at DESC);

-- ============================================================
-- BITACORA DE ACCESOS (append-only)
-- ============================================================
CREATE TABLE access_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE RESTRICT,
    gate_id         UUID NOT NULL REFERENCES gates(id) ON DELETE RESTRICT,
    shift_id        UUID REFERENCES guard_shifts(id) ON DELETE SET NULL,
    guard_id        UUID REFERENCES users(id) ON DELETE SET NULL,
    invitation_id   UUID REFERENCES invitations(id) ON DELETE SET NULL,
    lot_id          UUID REFERENCES lots(id) ON DELETE SET NULL,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,

    direction       VARCHAR(3)  NOT NULL CHECK (direction IN ('IN','OUT')),
    subject_type    VARCHAR(20) NOT NULL
                    CHECK (subject_type IN ('GUEST','RESIDENT','SERVICE','MANUAL')),
    result          VARCHAR(20) NOT NULL
                    CHECK (result IN ('ALLOWED','DENIED')),
    denial_reason   VARCHAR(30)
                    CHECK (denial_reason IN (
                        'INVALID_SIGNATURE','NOT_YET_VALID','EXPIRED',
                        'ALREADY_USED','REVOKED','OUT_OF_SCHEDULE',
                        'LOT_SUSPENDED','REPLAY','UNKNOWN')),

    subject_name    VARCHAR(140),
    subject_document VARCHAR(20),
    companions      SMALLINT NOT NULL DEFAULT 0,
    plate           VARCHAR(15),

    token_jti       UUID,
    captured_at     TIMESTAMPTZ NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    synced_offline  BOOLEAN NOT NULL DEFAULT FALSE,
    client_event_id UUID NOT NULL,

    CONSTRAINT chk_denial CHECK (
        (result = 'DENIED' AND denial_reason IS NOT NULL) OR
        (result = 'ALLOWED' AND denial_reason IS NULL)
    ),
    CONSTRAINT uq_client_event UNIQUE (client_event_id)
);
CREATE INDEX idx_logs_neighborhood_time ON access_logs (neighborhood_id, captured_at DESC);
CREATE INDEX idx_logs_invitation        ON access_logs (invitation_id);
CREATE INDEX idx_logs_lot               ON access_logs (lot_id, captured_at DESC);
CREATE INDEX idx_logs_open_stay         ON access_logs (neighborhood_id, direction, captured_at)
    WHERE result = 'ALLOWED';

CREATE OR REPLACE FUNCTION deny_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'access_logs es append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_access_logs_immutable
    BEFORE UPDATE OR DELETE ON access_logs
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();

-- ============================================================
-- DISPOSITIVOS (push)
-- ============================================================
CREATE TABLE devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expo_push_token VARCHAR(200) NOT NULL UNIQUE,
    platform        VARCHAR(10) NOT NULL CHECK (platform IN ('IOS','ANDROID')),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_devices_user ON devices (user_id);
