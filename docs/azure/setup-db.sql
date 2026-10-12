-- Configuracion inicial de la base en Azure. Se corre UNA vez, como el
-- administrador del servidor, conectado a la base "securepass":
--
--   psql "host=<SERVIDOR>.postgres.database.azure.com dbname=securepass user=<ADMIN> sslmode=require" \
--        -v app_user=securepass_app -v app_pass='<PASSWORD_APP>' -f docs/azure/setup-db.sql
--
-- Separa el usuario de la aplicacion del administrador: el backend se conecta
-- con un rol que solo es duenio de su base, no administra el servidor.

\set ON_ERROR_STOP on

CREATE ROLE :"app_user" LOGIN PASSWORD :'app_pass';

-- El admin necesita ser miembro del rol para cederle la base.
GRANT :"app_user" TO CURRENT_USER;
ALTER DATABASE securepass OWNER TO :"app_user";

-- V1__init.sql pide pgcrypto. En Azure solo el admin puede crear extensiones
-- (y solo las habilitadas en azure.extensions); la migracion despues la
-- encuentra creada y su IF NOT EXISTS no hace nada.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- En Azure el schema public pertenece a azure_pg_admin (no al duenio de la
-- base, como en un PostgreSQL 15+ comun): sin esto Flyway no puede crear
-- tablas.
ALTER SCHEMA public OWNER TO :"app_user";

SELECT current_database() AS base,
       pg_get_userbyid(datdba) AS duenio,
       (SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'public') AS duenio_public,
       (SELECT extversion FROM pg_extension WHERE extname = 'pgcrypto') AS pgcrypto
FROM pg_database WHERE datname = current_database();
