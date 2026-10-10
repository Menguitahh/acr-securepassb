-- securepassb#258: duenio de la plataforma, sin barrio. Corre en cada
-- arranque de dev (idempotente): las bases de dev creadas antes de #258
-- tambien lo tienen.
-- platform@dev.local / platform123
INSERT INTO users (id, email, password_hash, full_name, status) VALUES
('f0000001-0000-0000-0000-000000000000', 'platform@dev.local',
 '$2b$12$6VyN7wyc7zpCWWu6omC6t.00yVxSkH0vHjXMHPGGg1OlKcEE80Jw2', 'Dueño de la plataforma', 'ACTIVE')
ON CONFLICT (id) DO NOTHING;
INSERT INTO platform_admins (user_id) VALUES ('f0000001-0000-0000-0000-000000000000')
ON CONFLICT (user_id) DO NOTHING;
