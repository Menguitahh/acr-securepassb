-- WBS-101: tokens de activacion y recuperacion de contrasena.
-- El token en claro (256 bits) viaja en el link del email; en la base
-- guardamos solo su SHA-256 (32 bytes). Nunca hay forma de recuperar el
-- token original desde una lectura de la tabla.

CREATE TABLE activation_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL CHECK (purpose IN ('ACTIVATION', 'PASSWORD_RESET')),
    token_hash BYTEA NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_activation_token_hash_len CHECK (octet_length(token_hash) = 32),
    CONSTRAINT chk_activation_token_expires_future CHECK (expires_at > created_at)
);

-- Garantia de "un solo uso" a nivel de indice: dos inserts con el mismo
-- hash (incluidos los ya consumidos) chocan y no pueden coexistir.
CREATE UNIQUE INDEX ix_activation_tokens_hash ON activation_tokens(token_hash);

-- Consultas frecuentes: por usuario/proposito para reemisiones,
-- por expiracion para limpieza periodica.
CREATE INDEX ix_activation_tokens_user_purpose
    ON activation_tokens(user_id, purpose)
    WHERE consumed_at IS NULL;

CREATE INDEX ix_activation_tokens_expires
    ON activation_tokens(expires_at)
    WHERE consumed_at IS NULL;
