-- WBS-116: token permanente del trabajador.
--
-- Un trabajador tiene un unico QR activo por barrio, ligado a su user (no a
-- una invitacion). El QR se recompone en cada request de /me/worker-qr:
-- guardamos solo (user, barrio, jti, timestamps). La firma se rehace cada
-- vez con la signing_key activa del barrio (Ed25519 es deterministico para
-- el mismo seed+message).
--
-- por que no expira:
-- El QR es permanente por diseno. La exposicion contra "sacar una foto de la
-- pantalla del jardinero" se acota por tres cosas, no por vencimiento:
--   1. Franja horaria (WBS-114/117): fuera de martes/jueves 8-12 la foto
--      tambien rebota. Un jardinero no es "acceso 24/7 para siempre" — son
--      las N horas semanales que le declaro el titular.
--   2. Revocacion del titular: baja del ultimo permiso WORKER activo -> se
--      setea revoked_at + el JTI entra en /access/revoked. El scanner lo
--      descarta online y offline (§8.3.1: revoked_cache se refresca cada 60s).
--   3. Cara-a-DNI en garita. US-66 pide verificacion la primera vez; contra
--      un ladron que muestra la foto del QR ajeno, el DNI del ladron no
--      matchea con el DNI enrolado del titular del token.
-- El enrolamiento por DNI NO protege contra la foto en si — el JTI + firma
-- viajan con la persona del titular del token. Contra "sacan la foto y le
-- prestan el DNI al ladron", el algoritmo no puede; queda en manos del
-- guardia.
-- Reactivacion o alta de un WORKER post-baja emite un JTI NUEVO — el token
-- viejo queda revocado, foto del QR viejo pierde valor de forma permanente.
--
-- Upgrade path si el barrio pide rotacion programada:
--   ALTER TABLE worker_tokens ADD COLUMN expires_at TIMESTAMPTZ;
--   + cron que revoca donde expires_at < now(). Sin cambios en el scanner.
--
-- ponytail: unique parcial por (user_id) WHERE revoked_at IS NULL asume que
-- un mismo user no tiene workers activos en mas de un barrio a la vez. La
-- Fase 13 no cubre workers cross-barrio; si aparece, cambiar a
-- (user_id, neighborhood_id) con backfill trivial y quitar esta nota.

CREATE TABLE worker_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,
    jti             UUID NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);

-- Un solo token activo por user (ver ponytail: arriba).
CREATE UNIQUE INDEX idx_worker_tokens_one_active_per_user
    ON worker_tokens (user_id)
    WHERE revoked_at IS NULL;

-- /access/revoked filtra por barrio + since. Indice sobre las revocadas.
CREATE INDEX idx_worker_tokens_revoked_by_neighborhood
    ON worker_tokens (neighborhood_id, revoked_at)
    WHERE revoked_at IS NOT NULL;
