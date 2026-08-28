-- WBS-110: fecha de nacimiento del usuario. Nullable porque los usuarios
-- previos (admin, guard, residentes cargados sin este dato) no la tienen y
-- no la vamos a inventar. El alta de familiar (US-57) la exige y valida
-- edad >= 16 antes de aceptar el body; el padron de menores (WBS-111,
-- US-58) tendra su propia tabla con birth_date NOT NULL.
--
-- Guardamos birth_date, no age: la edad cambia con el tiempo, guardarla es
-- un bug esperando.

ALTER TABLE users
    ADD COLUMN birth_date DATE;
