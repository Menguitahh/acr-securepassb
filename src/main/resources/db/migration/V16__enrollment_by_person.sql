-- ============================================================
-- WBS-122 / US-69: verificacion de identidad por persona (no por invitacion)
--
-- Antes: `guest_enrollments` colgaba de `invitations` (UNIQUE por invitation_id).
-- Cada invitacion nueva del mismo DNI pedia enrolamiento otra vez, y el flujo
-- del trabajador ni siquiera lo consultaba.
--
-- Ahora: enrolamiento por (barrio, documento). Vale para invitaciones futuras
-- y para el trabajador (mismo endpoint, ruteo por subType en el use case).
-- La FK `invitation_id` queda como auditoria del primer enrolamiento cuando
-- vino por invitado; NULL cuando lo enrolo su primer ingreso como trabajador.
-- ============================================================

ALTER TABLE guest_enrollments
    ADD COLUMN neighborhood_id UUID REFERENCES neighborhoods(id) ON DELETE CASCADE;

UPDATE guest_enrollments ge
   SET neighborhood_id = i.neighborhood_id
  FROM invitations i
 WHERE ge.invitation_id = i.id
   AND ge.neighborhood_id IS NULL;

ALTER TABLE guest_enrollments
    ALTER COLUMN neighborhood_id SET NOT NULL;

-- invitation_id deja de ser obligatorio: los enrolamientos de trabajador
-- entran con NULL (no cuelgan de ninguna invitacion).
ALTER TABLE guest_enrollments
    ALTER COLUMN invitation_id DROP NOT NULL;

ALTER TABLE guest_enrollments
    DROP CONSTRAINT guest_enrollments_invitation_id_key;

-- Dedup antes del UNIQUE nuevo. Si existen filas duplicadas por
-- (neighborhood_id, document_number) (dos invitaciones al mismo DNI ya
-- verificadas), conservamos el enrolled_at mas viejo — la verificacion
-- original vale, es la primera vez que la persona se paro adelante del
-- guardia. Desempate por id para que la migracion sea deterministica.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY neighborhood_id, document_number
               ORDER BY enrolled_at ASC, id ASC
           ) AS rn
      FROM guest_enrollments
)
DELETE FROM guest_enrollments
 WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

ALTER TABLE guest_enrollments
    ADD CONSTRAINT uq_enrollment_person_neighborhood
    UNIQUE (neighborhood_id, document_number);

-- El UNIQUE compuesto cubre lookups por (neighborhood_id, document_number).
-- No hay caller para "documento sin barrio": el endpoint /access/enroll
-- siempre resuelve neighborhood_id del JWT, y validate lo saca del payload.
DROP INDEX IF EXISTS idx_enrollments_document;
