-- Seed de desarrollo para levantar el backend sin padron por API (WBS-11 fuera de tanda).
-- Idempotente: se puede correr multiples veces. IDs fijos para no tener que ir a buscarlos.
--
-- Usuarios:
--   admin@dev.local    / admin123     ADMIN
--   guard@dev.local    / guard123     GUARD
--   owner@dev.local    / resident123  RESIDENT_OWNER (dueño del lote A-001)
--
-- BCrypt cost 12. Hashes generados con la libreria bcrypt de python; formato $2b$
-- que Spring's BCryptPasswordEncoder acepta sin problemas.

BEGIN;

-- Barrio
INSERT INTO neighborhoods (id, name, slug, timezone, stay_alert_minutes, active)
VALUES ('11111111-1111-1111-1111-111111111111',
        'Barrio Dev', 'barrio-dev', 'America/Argentina/Mendoza', 480, TRUE)
ON CONFLICT (id) DO NOTHING;

-- Garita
INSERT INTO gates (id, neighborhood_id, name, direction, active)
VALUES ('22222222-2222-2222-2222-222222222222',
        '11111111-1111-1111-1111-111111111111',
        'Garita Principal', 'BOTH', TRUE)
ON CONFLICT (id) DO NOTHING;

-- Lote
INSERT INTO lots (id, neighborhood_id, code, street, number, status)
VALUES ('33333333-3333-3333-3333-333333333333',
        '11111111-1111-1111-1111-111111111111',
        'A-001', 'Calle Falsa', '123', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Usuarios (password_hash BCrypt cost 12)
INSERT INTO users (id, email, password_hash, full_name, status) VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
 'admin@dev.local',
 '$2b$12$Ns1P2l9xRe.nBoT3cNZf1OiEJg/yqkBaLurStZEkYGkFSdiD1p.ia',
 'Admin Dev', 'ACTIVE'),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
 'guard@dev.local',
 '$2b$12$UuP2MU6Usc8OezBk4bfwHul6QI/p0JBpoXl.u5A8gEo5A0Qv/xJSW',
 'Guard Dev', 'ACTIVE'),
('cccccccc-cccc-cccc-cccc-cccccccccccc',
 'owner@dev.local',
 '$2b$12$kjdZJ.PTi67ZgDxwjlyzDulD61436GZP4gYTpDdxfkIjwUNgNfxk.',
 'Owner Dev', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- Memberships
INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES
('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', '11111111-1111-1111-1111-111111111111', 'ADMIN', TRUE),
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'GUARD', TRUE),
('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'RESIDENT_OWNER', TRUE)
ON CONFLICT (user_id, neighborhood_id, role) DO NOTHING;

-- Vinculo owner -> lote
INSERT INTO family_members (lot_id, user_id, relation, is_owner, can_invite, active)
VALUES ('33333333-3333-3333-3333-333333333333',
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        'OWNER', TRUE, TRUE, TRUE)
ON CONFLICT (lot_id, user_id) DO NOTHING;

-- WBS-106: escritura dual. El seed inserta family_members con SQL directo
-- (no pasa por FamilyMemberJdbcRepository.save), asi que hay que replicar
-- en lot_permissions con el mismo mapeo que usa la V10, si no cada reset
-- de dev deja las dos tablas desincronizadas y WBS-108 (al invertir la
-- fuente de verdad) rompe el permiso del owner del seed.
INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc',
        '33333333-3333-3333-3333-333333333333',
        'OWNER',
        jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'),
        TRUE)
ON CONFLICT (user_id, lot_id, permission_type) DO NOTHING;

COMMIT;
