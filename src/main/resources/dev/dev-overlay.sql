-- Overlay dev-only. Corre DESPUES de NeighborhoodProvisioner en DevSeedRunner.
-- El provisioner ya creo: neighborhood, signing_key, gate, admin PENDING_ACTIVATION,
-- membership admin y activation_token.
--
-- Este overlay agrega lo que no es parte del provisioning de un barrio real:
--   - guard y owner con passwords conocidas (dev)
--   - lot A-001 y el lot_permissions OWNER del owner
-- Y ademas activa al admin del seed con hash conocido.
--
-- Passwords: admin123 / guard123 / resident123 (BCrypt cost 12).

BEGIN;

-- Deliberado dev-only: saltamos el flujo de activacion del admin. En prod el
-- admin pasa por PENDING_ACTIVATION -> mail con token -> setea password. Aca
-- forzamos ACTIVE con hash conocido para que el dev pueda loguear sin abrir
-- el mailer. No es un bug: si algun dia esta linea desaparece, el admin del
-- seed no va a poder loguear con admin123 y algo mas rompio antes.
UPDATE users
   SET status = 'ACTIVE',
       password_hash = '$2b$12$Ns1P2l9xRe.nBoT3cNZf1OiEJg/yqkBaLurStZEkYGkFSdiD1p.ia'
 WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
   AND password_hash IS NULL;

-- Guard y owner (no los crea el provisioner: son dev-only).
INSERT INTO users (id, email, password_hash, full_name, status) VALUES
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
 'guard@dev.local',
 '$2b$12$UuP2MU6Usc8OezBk4bfwHul6QI/p0JBpoXl.u5A8gEo5A0Qv/xJSW',
 'Guard Dev', 'ACTIVE'),
('cccccccc-cccc-cccc-cccc-cccccccccccc',
 'owner@dev.local',
 '$2b$12$kjdZJ.PTi67ZgDxwjlyzDulD61436GZP4gYTpDdxfkIjwUNgNfxk.',
 'Owner Dev', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'GUARD', TRUE),
('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'RESIDENT_OWNER', TRUE)
ON CONFLICT (user_id, neighborhood_id, role) DO NOTHING;

INSERT INTO lots (id, neighborhood_id, code, street, number, status)
VALUES ('33333333-3333-3333-3333-333333333333',
        '11111111-1111-1111-1111-111111111111',
        'A-001', 'Calle Falsa', '123', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
VALUES ('cccccccc-cccc-cccc-cccc-cccccccccccc',
        '33333333-3333-3333-3333-333333333333',
        'OWNER',
        jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'),
        TRUE)
ON CONFLICT (user_id, lot_id, permission_type) DO NOTHING;

COMMIT;
