ALTER TABLE invitations
    ADD COLUMN jti UUID NOT NULL DEFAULT gen_random_uuid();

ALTER TABLE invitations
    ADD CONSTRAINT uq_invitations_jti UNIQUE (jti);

CREATE INDEX idx_invitations_revoked
    ON invitations (neighborhood_id, revoked_at DESC)
    WHERE status = 'REVOKED';
