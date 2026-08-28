-- WBS-108 PR3: drop family_members. La fuente de verdad es lot_permissions
-- desde V10 (backfill + validacion uno-a-uno) y desde entonces todos los
-- reads/writes de la app viven ahi. El dual write se removio en el commit
-- anterior a este drop; sin la tabla, cualquier caller viejo falla al
-- arrancar en vez de silenciar el drift.
--
-- No hay backfill: V10 ya copio los datos. No hay FK apuntando a esta
-- tabla (grep en migrations lo confirma).

DROP INDEX IF EXISTS idx_family_members_lot;
DROP TABLE family_members;
