-- Overlay dev-only. Corre DESPUES de NeighborhoodProvisioner en DevSeedRunner.
-- El provisioner ya creo: neighborhood, signing_key, gate, admin PENDING_ACTIVATION,
-- membership admin y activation_token.
--
-- Este overlay agrega lo que no es parte del provisioning de un barrio real:
--   - guard y owner con passwords conocidas (dev)
--   - lot A-001 y el lot_permissions OWNER del owner
--   - Escenario extendido de testing device (lote A-14 con owner/family/menores
--     y dos users de conflicto). Ver bloque marcado abajo.
-- Y ademas activa al admin del seed con hash conocido.
--
-- Passwords: admin123 / guard123 / resident123 / OwnerPass123! / FamilyPass123!
-- / WorkerPass123! (BCrypt cost 12).
--
-- Idempotencia: cada INSERT lleva ON CONFLICT DO NOTHING sobre la PK o la
-- unique key natural. Correr este script dos veces sobre una DB con el estado
-- ya sembrado no explota ni duplica. DevSeedRunner ademas se autoprotege via
-- chequeo previo del barrio (skip completo si ya existe), asi que el escenario
-- normal es "una sola corrida"; el ON CONFLICT cubre el path de ejecutar el
-- SQL a mano contra una base con estado parcial.

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

-- Guard y owner del barrio dev (no los crea el provisioner: son dev-only).
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
('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', '11111111-1111-1111-1111-111111111111', 'ROLE_GUARD', TRUE),
('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111', 'ROLE_RESIDENT_OWNER', TRUE)
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

-- =====================================================================
-- Escenario extendido de device-testing (lote A-14). Los datos aca replican
-- lo que originalmente sembrabamos a mano por API durante la sesion de
-- pruebas. Al meterlo aca, un solo run del DevSeedRunner devuelve el
-- escenario completo aun despues de un reset de la DB.
--
-- Convencion de UUIDs fijos:
--   44444444-...        -> lote A-14 y menores
--   d0000001-...        -> owner@test.com   (Ana Titular)
--   d0000002-...        -> family@test.com  (Beto Familiar)
--   d0000003-...        -> suspended@test.com
--   d0000004-...        -> deleted@test.com
-- =====================================================================

-- Users test.com. Passwords fijas (OwnerPass123! / FamilyPass123!) via hash
-- pre-generado. Los dos users de conflicto van sin password: se los usa para
-- ejercitar SUSPENDED y DELETED (login rechazado), no para loguearse.
INSERT INTO users (id, email, password_hash, full_name, document_number, status) VALUES
('d0000001-0000-0000-0000-000000000000',
 'owner@test.com',
 '$2b$12$YDFAGjXGeJ7wA7nweukQRePAF6rtNUOD5Vq4ukVoto4CQKYiKIu0W',
 'Ana Titular', '30000010', 'ACTIVE'),
('d0000002-0000-0000-0000-000000000000',
 'family@test.com',
 '$2b$12$liwxfX2UsYdneNPWAbtLduiGH9yXOIWs/tVAYHBzZjjYM64qrjLg.',
 'Beto Familiar', '30000011', 'ACTIVE'),
('d0000003-0000-0000-0000-000000000000',
 'suspended@test.com', NULL,
 'User suspended', '30777001', 'SUSPENDED'),
('d0000004-0000-0000-0000-000000000000',
 'deleted@test.com', NULL,
 'User deleted', '30777002', 'DELETED')
ON CONFLICT (id) DO NOTHING;

INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES
('d0000001-0000-0000-0000-000000000000',
 '11111111-1111-1111-1111-111111111111', 'ROLE_RESIDENT_OWNER', TRUE),
('d0000002-0000-0000-0000-000000000000',
 '11111111-1111-1111-1111-111111111111', 'ROLE_RESIDENT_MEMBER', TRUE)
ON CONFLICT (user_id, neighborhood_id, role) DO NOTHING;

INSERT INTO lots (id, neighborhood_id, code, street, number, status)
VALUES ('44444444-4444-4444-4444-444444444444',
        '11111111-1111-1111-1111-111111111111',
        'A-14', 'Calle Falsa', '14', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
VALUES ('d0000001-0000-0000-0000-000000000000',
        '44444444-4444-4444-4444-444444444444', 'OWNER',
        jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE),
       ('d0000002-0000-0000-0000-000000000000',
        '44444444-4444-4444-4444-444444444444', 'MEMBER',
        jsonb_build_object('can_invite', FALSE, 'relation', 'hermano'), TRUE)
ON CONFLICT (user_id, lot_id, permission_type) DO NOTHING;

-- Menores del padron. birth_date se calcula desde CURRENT_DATE para que
-- (a) sobrevivan al chk_minor_age (< 17 anios) el ano que viene, y
-- (b) M5 quede siempre en el borde exacto: un dia antes de cumplir 16.
INSERT INTO lot_minors (id, lot_id, neighborhood_id, full_name, document_number,
                        birth_date, relation, notes, created_by) VALUES
('44444444-0000-0000-0000-000000000001',
 '44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
 'Mateo Ninio', '55111111',
 CURRENT_DATE - INTERVAL '10 years' - INTERVAL '170 days',
 'hijo', 'Nino con DNI y notas',
 'd0000001-0000-0000-0000-000000000000'),
('44444444-0000-0000-0000-000000000002',
 '44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
 'Lola Ninia', NULL,
 CURRENT_DATE - INTERVAL '10 years' - INTERVAL '43 days',
 'hija', NULL,
 'd0000001-0000-0000-0000-000000000000'),
('44444444-0000-0000-0000-000000000003',
 '44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
 'Sofia Casi16', '55333333',
 CURRENT_DATE - INTERVAL '15 years' - INTERVAL '203 days',
 'hija', 'Adolescente con DNI',
 'd0000001-0000-0000-0000-000000000000'),
('44444444-0000-0000-0000-000000000004',
 '44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
 'Tomas Casi16', NULL,
 CURRENT_DATE - INTERVAL '15 years' - INTERVAL '88 days',
 'hijo', NULL,
 'd0000001-0000-0000-0000-000000000000'),
('44444444-0000-0000-0000-000000000005',
 '44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111',
 'Borde Interior', '55555555',
 CURRENT_DATE - INTERVAL '16 years' + INTERVAL '1 day',
 'hijo', 'Edge case: exacto 16 al dia siguiente',
 'd0000001-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================================
-- Escenario worker (WBS-118..121). El backend de Fase 13 esta cerrado
-- (WBS-114..117, WBS-122) pero el mobile todavia no arranco. Sin este
-- bloque el front no tiene con que probar: worker_tokens siempre esta
-- vacia porque el token se crea lazy al primer GET /me/worker-qr, y sin
-- un permiso WORKER activo el endpoint devuelve 404.
--
-- Se siembra:
--   - user d0000005 (worker@test.com, DNI 30999001, ACTIVE) con password
--     WorkerPass123! (BCrypt cost 12, hash pre-generado).
--   - lote extra A-15 (55555555-...) del mismo owner@test.com, para
--     habilitar US-67 (picker de lote en garita, WBS-119).
--   - permiso WORKER activo en DOS lotes:
--       * A-14 con franja amplia (L a D, 00:00-23:59) para que el flujo
--         golden entre a cualquier hora.
--       * A-15 con franja angosta (solo lunes, 09:00-10:00) para probar
--         OUT_OF_SCHEDULE. La ventana angosta convive con la amplia: el
--         evaluateWorker las evalua ambas y devuelve AllowedWorker con
--         los lotes en franja, lo que ejercita el picker.
--   - No se siembra fila en worker_tokens: IssueWorkerTokenUseCase la
--     crea lazy al primer GET /me/worker-qr y firma con la signing key
--     activa del barrio. Ejercita el flujo real.
--   - No se siembra guest_enrollments para el DNI 30999001: el primer
--     scan en garita devuelve NEEDS_ENROLLMENT.WORKER y el guardia
--     carga el DNI para completar el enrolamiento (WBS-122).
-- =====================================================================

INSERT INTO users (id, email, password_hash, full_name, document_number, status) VALUES
('d0000005-0000-0000-0000-000000000000',
 'worker@test.com',
 '$2b$12$R8StHQzyro7QZZkhkpRZA.GySx4FWPjzKzWcurXhov0D9VRouthsO',
 'Wanda Trabajadora', '30999001', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

INSERT INTO memberships (user_id, neighborhood_id, role, active) VALUES
('d0000005-0000-0000-0000-000000000000',
 '11111111-1111-1111-1111-111111111111', 'ROLE_RESIDENT_MEMBER', TRUE)
ON CONFLICT (user_id, neighborhood_id, role) DO NOTHING;

INSERT INTO lots (id, neighborhood_id, code, street, number, status)
VALUES ('55555555-5555-5555-5555-555555555555',
        '11111111-1111-1111-1111-111111111111',
        'A-15', 'Calle Falsa', '15', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;

-- owner@test.com como OWNER del segundo lote, para que el mismo titular
-- vea ambos workers en su UI de gestion (WBS-121).
INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
VALUES ('d0000001-0000-0000-0000-000000000000',
        '55555555-5555-5555-5555-555555555555', 'OWNER',
        jsonb_build_object('can_invite', TRUE, 'relation', 'OWNER'), TRUE)
ON CONFLICT (user_id, lot_id, permission_type) DO NOTHING;

-- Permiso WORKER amplio en A-14: entra siempre.
INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
VALUES ('d0000005-0000-0000-0000-000000000000',
        '44444444-4444-4444-4444-444444444444', 'WORKER',
        jsonb_build_object(
            'service',    'Limpieza',
            'weekdays',   jsonb_build_array(1,2,3,4,5,6,7),
            'dailyFrom',  '00:00',
            'dailyUntil', '23:59'),
        TRUE)
ON CONFLICT (user_id, lot_id, permission_type) DO NOTHING;

-- Permiso WORKER angosto en A-15: solo lunes 09:00-10:00.
INSERT INTO lot_permissions (user_id, lot_id, permission_type, attributes, active)
VALUES ('d0000005-0000-0000-0000-000000000000',
        '55555555-5555-5555-5555-555555555555', 'WORKER',
        jsonb_build_object(
            'service',    'Jardineria',
            'weekdays',   jsonb_build_array(1),
            'dailyFrom',  '09:00',
            'dailyUntil', '10:00'),
        TRUE)
ON CONFLICT (user_id, lot_id, permission_type) DO NOTHING;

COMMIT;
