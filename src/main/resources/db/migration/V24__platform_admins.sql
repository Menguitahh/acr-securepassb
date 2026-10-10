-- securepassb#258: duenios de la plataforma. No pertenecen a ningun barrio
-- (por eso no van en memberships): crean barrios y entran a ver cualquiera
-- en modo lectura. El primero se da de alta a mano:
--   INSERT INTO platform_admins (user_id) SELECT id FROM users WHERE email = '...';
CREATE TABLE platform_admins (
    user_id    UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
