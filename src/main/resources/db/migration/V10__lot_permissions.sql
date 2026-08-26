-- WBS-106: tabla nueva para permisos por lote.
--
-- Reemplaza a family_members como fuente de "quien ocupa este lote y con
-- que rol". memberships queda para roles de gestion del barrio (ADMIN,
-- GUARD): un guardia no ocupa ningun lote, meterlo en uno seed seria
-- inventar un dato falso. WBS-108 va a evaluar @PreAuthorize contra
-- lot_permissions para lo residencial/laboral y contra memberships para
-- lo administrativo.
--
-- Migracion aditiva a nivel esquema: crea tabla nueva, copia datos, NO
-- borra family_members. El drop viene en un PR posterior a WBS-108,
-- cuando ningun caller la use.
--
-- En el mismo PR se habilita ESCRITURA DUAL: FamilyMemberJdbcRepository y
-- LotMemberJdbcRepository escriben en las dos tablas en la misma
-- transaccion. Fuente de verdad sigue siendo family_members. Sin dual
-- write, cada alta post-migracion desincronizaba lot_permissions
-- silenciosamente. WBS-108 invierte la fuente de verdad y saca el dual
-- write en el mismo PR.

CREATE TABLE lot_permissions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    lot_id          UUID NOT NULL REFERENCES lots(id) ON DELETE CASCADE,
    permission_type VARCHAR(20) NOT NULL
                    CHECK (permission_type IN ('OWNER','MEMBER','WORKER')),
    -- Ejemplos: OWNER: {}, MEMBER: {"can_invite": true, "relation": "Hijo/a"},
    -- WORKER (Fase 13, WBS-114): {"service": "...", "days": [...], "time_from": "...", "time_to": "..."}
    attributes      JSONB NOT NULL DEFAULT '{}'::jsonb,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Nota para WBS-108: el UNIQUE permite que la misma persona tenga
    -- OWNER y MEMBER simultaneos sobre el mismo lote. La migracion nunca
    -- lo produce (family_members tiene UNIQUE (lot_id, user_id) sin
    -- permission_type). Si WBS-108 quiere evitar esa combinacion, la
    -- validacion va en la capa de aplicacion, no aca.
    UNIQUE (user_id, lot_id, permission_type)
);

CREATE INDEX idx_lot_permissions_user ON lot_permissions (user_id) WHERE active;
CREATE INDEX idx_lot_permissions_lot  ON lot_permissions (lot_id)  WHERE active;

-- Copia one-to-one. is_owner=true -> OWNER; is_owner=false -> MEMBER.
-- can_invite y relation entran en attributes; el resto de columnas de
-- family_members (id, created_at, active) preservan su valor.
INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active, created_at)
SELECT
    user_id,
    lot_id,
    CASE WHEN is_owner THEN 'OWNER' ELSE 'MEMBER' END,
    jsonb_build_object('can_invite', can_invite, 'relation', relation),
    active,
    created_at
FROM family_members;

-- Verificacion "sin perdida". Falla la migracion si algo no cierra.
DO $$
DECLARE
    fm_count   INT;
    lp_count   INT;
    mismatch   INT;
BEGIN
    SELECT count(*) INTO fm_count FROM family_members;
    SELECT count(*) INTO lp_count FROM lot_permissions;
    IF fm_count != lp_count THEN
        RAISE EXCEPTION 'WBS-106: count mismatch family_members=% lot_permissions=%',
                        fm_count, lp_count;
    END IF;

    -- Cada family_members tiene su equivalente en lot_permissions con el
    -- mismo user, lot, tipo derivado, active, y attributes coherentes.
    SELECT count(*) INTO mismatch
    FROM family_members f
    LEFT JOIN lot_permissions p
      ON p.user_id = f.user_id
     AND p.lot_id  = f.lot_id
     AND p.permission_type = CASE WHEN f.is_owner THEN 'OWNER' ELSE 'MEMBER' END
     AND p.active  = f.active
     AND (p.attributes->>'can_invite')::boolean = f.can_invite
     AND (p.attributes->>'relation') IS NOT DISTINCT FROM f.relation
    WHERE p.id IS NULL;
    IF mismatch > 0 THEN
        RAISE EXCEPTION 'WBS-106: % family_members rows without matching lot_permissions', mismatch;
    END IF;
END $$;
