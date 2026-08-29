-- WBS-114: indice parcial para lookup por usuario del permiso WORKER.
--
-- /access/validate del trabajador (WBS-117) resuelve el sujeto del QR
-- permanente contra sus permisos WORKER activos, cruzando barrios y lotes.
-- El index general idx_lot_permissions_user (V10) sirve, pero incluye OWNER y
-- MEMBER y no filtra por tipo; a mediano plazo el WHERE permission_type
-- ='WORKER' se paga en cada validacion. El indice parcial va directo a la
-- particion que necesitamos.
--
-- No hay cambio de schema. Es puramente performance.

CREATE INDEX idx_lot_permissions_worker_user
    ON lot_permissions (user_id)
    WHERE active AND permission_type = 'WORKER';
