-- securepassb#256: el rol se guarda con el prefijo de Spring Security
-- (ROLE_ADMIN, ROLE_GUARD...), el mismo valor que la authority del usuario
-- autenticado. La API y el JWT siguen usando el nombre sin prefijo.
ALTER TABLE memberships DROP CONSTRAINT memberships_role_check;
UPDATE memberships SET role = 'ROLE_' || role WHERE role NOT LIKE 'ROLE_%';
ALTER TABLE memberships ALTER COLUMN role TYPE VARCHAR(30);
ALTER TABLE memberships ADD CONSTRAINT memberships_role_check
    CHECK (role IN ('ROLE_ADMIN', 'ROLE_GUARD', 'ROLE_RESIDENT_OWNER', 'ROLE_RESIDENT_MEMBER'));
