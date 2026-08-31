-- ============================================================
-- WBS-127 / US-74: eliminar el tipo RECURRING de invitations.
-- Los trabajadores lo reemplazan (Fase 13). Esta migracion transforma
-- las filas RECURRING existentes en su equivalente EVENT y saca las
-- columnas de franja. El pase personal del residente se preserva
-- IN PLACE (mismo id + mismo jti) para no romper los QR ya emitidos
-- en dispositivos: eso mantiene la firma Ed25519 valida contra la fila
-- migrada. Ver comentario en QrTokenPayload sobre la tolerancia al
-- valor legacy "R" en el codec.
-- ============================================================

-- Paso 1: pase del residente (residentPass=true) — UPDATE in place.
-- El QR pre-V17 sigue apuntando a esta fila con el mismo jti. Al leer,
-- Invitation.type ahora es EVENT y residentPass=true exenta de BR-04
-- (ver Invitation.java:71); validate ya no evalua ScheduleWindow porque
-- se eliminaba solo con type=RECURRING, y esa rama tambien se cae.
UPDATE invitations
   SET type='EVENT', weekdays=NULL, daily_from=NULL, daily_until=NULL, updated_at=now()
 WHERE resident_pass = TRUE AND type = 'RECURRING';

-- Paso 2: invitaciones de invitado RECURRING — se revocan.
-- US-74 acepta "migracion a permisos de trabajador o revocacion".
-- Migrar automatico a WORKER no es viable sin adivinar titular/DNI/franja;
-- revocacion es la opcion conservadora. La FK access_logs.invitation_id
-- ON DELETE SET NULL queda intacta: los logs conservan el link.
--
-- valid_until queda intacto. La ventana original de una RECURRING
-- historica puede exceder 30 dias (limite de EVENT en BR-04), pero
-- Invitation.java solo evalua BR-04 sobre filas ACTIVE (ver comentario
-- ahi) — el pasado no se re-audita. Asi el dato historico se preserva
-- y sigue siendo visible con la fecha original en /invitations y
-- /invitations/{id}, para no adulterar la vista de auditoria del admin.
UPDATE invitations
   SET type='EVENT',
       weekdays=NULL, daily_from=NULL, daily_until=NULL,
       status='REVOKED',
       revoked_at=COALESCE(revoked_at, now()),
       updated_at=now()
 WHERE resident_pass = FALSE AND type = 'RECURRING';

-- Paso 3: bajar los CHECK que referencian RECURRING.
ALTER TABLE invitations DROP CONSTRAINT invitations_type_check;
ALTER TABLE invitations ADD CONSTRAINT invitations_type_check
    CHECK (type IN ('SINGLE','EVENT'));

-- El CHECK chk_recurring_slot exigia weekdays/daily_from/daily_until para
-- RECURRING (V1:136). Al eliminarse el tipo y las columnas, no tiene
-- sentido. Se dropea antes que las columnas.
ALTER TABLE invitations DROP CONSTRAINT IF EXISTS chk_recurring_slot;

-- Paso 4: drop de columnas de franja. Ya no las lee nadie.
ALTER TABLE invitations DROP COLUMN weekdays;
ALTER TABLE invitations DROP COLUMN daily_from;
ALTER TABLE invitations DROP COLUMN daily_until;
