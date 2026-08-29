-- WBS-111 / US-58: padron de menores del lote.
--
-- Personas del lote sin usuario ni credenciales. No entran a `users` porque:
--   - No loguean, no generan QR, no aparecen en /access/validate,
--     no forman parte del revoked_cache. Puramente registro documental.
--   - `users` exige email o phone (User invariant); un menor no tiene.
--   - UNIQUE global de `users.document_number` (V9) chocaria al graduar
--     al menor a cuenta real (WBS-110 flow) porque el DNI ya existiria.
--     La graduacion es una transaccion: DELETE FROM lot_minors + alta
--     por AddFamilyMemberUseCase; asi el UNIQUE se aplica sobre el
--     unico lugar donde importa (identidad del sistema).
--
-- Isla aislada de lot_permissions: los menores no tienen permiso.

CREATE TABLE lot_minors (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lot_id          UUID NOT NULL REFERENCES lots(id) ON DELETE RESTRICT,
    -- neighborhood_id denormalizado para aislamiento por barrio (spec 5.5)
    -- sin JOIN al lot en cada query. La consistencia se mantiene por el
    -- INSERT del use case (siempre saca el neighborhood_id del lote).
    neighborhood_id UUID NOT NULL REFERENCES neighborhoods(id) ON DELETE CASCADE,

    full_name       VARCHAR(140) NOT NULL,
    -- OPCIONAL: un chico de 5 anios puede no tener DNI todavia. La app lo
    -- pide obligatorio recien al graduar (WBS-110 lo exige).
    document_number VARCHAR(20),
    -- Obligatoria por spec WBS-111 ("edad obligatoria"). Guardamos fecha,
    -- no edad: la edad cambia sola, guardarla es un bug esperando.
    birth_date      DATE NOT NULL,
    relation        VARCHAR(30),
    notes           VARCHAR(280),
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Deliberado 17, no 16. La regla de la app es "menor de 16 al momento
    -- del alta". Este CHECK es cinturon: hasta que el chico cumpla 17 la
    -- fila sigue siendo valida aunque el titular no lo haya graduado a los
    -- 16. Sin este colchon, la fila se volveria invalida el dia del
    -- cumpleanios y bloquearia cualquier UPDATE (edicion de notes, etc)
    -- por el propio CHECK, sin que el titular haya hecho nada. El gap
    -- 16-17 es la ventana de UX para graduar; despues de eso el CHECK
    -- protege al padron de datos zombie mas viejos.
    CONSTRAINT chk_minor_age CHECK (birth_date > (CURRENT_DATE - INTERVAL '17 years'))
);
CREATE INDEX idx_lot_minors_lot ON lot_minors (lot_id);
CREATE INDEX idx_lot_minors_neighborhood ON lot_minors (neighborhood_id);

-- Evita cargar dos veces al mismo menor en el mismo lote (dedo pesado).
-- Partial index porque el DNI es opcional; dos menores sin DNI no chocan.
CREATE UNIQUE INDEX uq_lot_minors_lot_document
    ON lot_minors (lot_id, document_number)
    WHERE document_number IS NOT NULL;
