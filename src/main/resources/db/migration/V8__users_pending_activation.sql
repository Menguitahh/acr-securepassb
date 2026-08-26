-- WBS-102: los usuarios creados por alta admin/titular/trabajador arrancan
-- en PENDING_ACTIVATION hasta que el usuario define su contrasena via el
-- enlace del email. Login rechaza PENDING_ACTIVATION igual que SUSPENDED.

ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE', 'PENDING_ACTIVATION', 'SUSPENDED', 'DELETED'));
