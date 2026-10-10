-- securepassb#121: la suspension sigue siendo global (users.status), pero
-- queda registrado que barrio la puso, cuando y por que. Con eso el admin de
-- otro barrio ve el motivo en el 409 del alta y puede pedir la revision.
ALTER TABLE users
    ADD COLUMN suspended_by_neighborhood UUID REFERENCES neighborhoods (id),
    ADD COLUMN suspended_reason VARCHAR(280),
    ADD COLUMN suspended_at TIMESTAMPTZ;
