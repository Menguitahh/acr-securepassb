-- WBS-128: alta de usuario por admin.
--
-- password_hash NULL: los usuarios creados por admin/titular/trabajador
-- arrancan en PENDING_ACTIVATION sin contrasena. Definir "" o placeholder
-- abriria un vector (el placeholder podria coincidir con un bcrypt patologico
-- si alguien fuerza status=ACTIVE). null es explicito: cannot log in hasta
-- que activateWithPassword la reemplace.

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- v2 R1: una persona, un usuario. El DNI identifica a la persona en TODO
-- el sistema, no por barrio. Un DNI en el padron de dos barrios distintos
-- reusa el mismo user. Partial: usuarios sin document_number (edge case)
-- no chocan entre si.
CREATE UNIQUE INDEX users_document_number_unique
    ON users (document_number)
    WHERE document_number IS NOT NULL;
