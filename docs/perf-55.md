# Perf 5.5 — EXPLAIN ANALYZE de las consultas frecuentes (WBS-55)

Objetivo: `p95 /access/validate < 200 ms`, sin seq scan en tablas >10k.

Metodologia: se seedearon 100k `access_logs`, 5k `invitations`, 2k `stays`
en Postgres 16 (Docker) y se corrio `EXPLAIN (ANALYZE, BUFFERS)` sobre las
cinco consultas mas frecuentes en produccion. Los planes se resumen abajo;
las tablas completas viven en `docs/perf-55-plans.txt` (regenerable con
`docs/perf-55-load.sql`).

## Las 5 consultas mas frecuentes

### 1. `POST /access/validate` -> `invitations.findById(id)`

```sql
SELECT ... FROM invitations WHERE id = :id;
```

- Plan: `Index Scan using invitations_pkey`, `cost=0.42..8.44`, `actual=0.021..0.023 ms`.
- Sin seq scan.

### 2. `POST /access/validate` -> `signing_keys.findByKid(nid, kid)`

```sql
SELECT ... FROM signing_keys WHERE neighborhood_id = :nid AND kid = :kid;
```

- Plan: `Index Scan using signing_keys_neighborhood_id_kid_key`, `actual=0.015..0.017 ms`.
- Cubierto por `UNIQUE (neighborhood_id, kid)` de V1.

### 3. `POST /access/validate` -> `lots.findById(id)` (via LotJdbcQuery.find)

```sql
SELECT ... FROM lots l JOIN neighborhoods n ON n.id = l.neighborhood_id WHERE l.id = :id;
```

- Plan: `Nested Loop`, `Index Scan using lots_pkey`, `Index Scan using neighborhoods_pkey`.
- Sin seq scan.

### 4. `GET /access-logs?from=&to=&lotId=&result=`

```sql
SELECT al.*, l.code FROM access_logs al
  LEFT JOIN lots l ON l.id = al.lot_id
 WHERE al.neighborhood_id = :nid
   AND al.lot_id = ANY(:lots)              -- residente
   AND al.captured_at BETWEEN :from AND :to
   AND al.result = :result
 ORDER BY al.captured_at DESC LIMIT 25 OFFSET :o;
```

- Plan con `lot_id = ANY(...)`: `Bitmap Index Scan on idx_logs_lot`
  (`(lot_id, captured_at DESC)` de V1) + Bitmap Heap Scan filtrado por
  `captured_at` y `result`. Sin seq scan.
- Plan sin `lot_id` (ADMIN): `Index Scan Backward using idx_logs_neighborhood_time`
  con condicion `captured_at >= :from` como filtro de index. Sin seq scan.
- Riesgo: si el rango temporal cubre casi toda la tabla, el optimizador
  puede degradar a Bitmap Heap Scan pero sigue usando indice. En el rango
  MVP (90 dias por default) esta acotado.

### 5. `GET /stays/open`

```sql
SELECT ... FROM stays s LEFT JOIN lots l ON l.id = s.lot_id
 WHERE s.neighborhood_id = :nid AND s.exited_at IS NULL
 ORDER BY s.entered_at DESC;
```

- Plan: `Index Scan using idx_stays_open`
  (`(neighborhood_id, expected_until) WHERE exited_at IS NULL` de V4) +
  Sort por entered_at. El index parcial elimina las stays cerradas del
  scan. Sin seq scan.

## Indices verificados

Todos presentes desde V1..V5:

| Tabla | Indice | Cubre |
|---|---|---|
| `invitations` | `invitations_pkey` | validate.findById |
| `invitations` | `idx_invitations_lot (lot_id, created_at DESC)` | listado por lote |
| `invitations` | `idx_invitations_active (neighborhood_id, status) WHERE ACTIVE` | conteo/listado active |
| `invitations` | `idx_invitations_revoked` (V2) | revoked cache |
| `invitations` | `uq_invitations_resident_pass (created_by, lot_id) WHERE resident_pass AND ACTIVE` (V5) | resident pass lookup |
| `signing_keys` | `UNIQUE (neighborhood_id, kid)` | validate.findByKid |
| `signing_keys` | `uq_signing_keys_active` | listado activa |
| `access_logs` | `idx_logs_neighborhood_time (neighborhood_id, captured_at DESC)` | bitacora admin |
| `access_logs` | `idx_logs_invitation` | correlacion por invitacion |
| `access_logs` | `idx_logs_lot (lot_id, captured_at DESC)` | bitacora residente |
| `access_logs` | `idx_logs_open_stay` (parcial) | queries de ocupacion |
| `access_logs` | `uq_client_event` (constraint) | idempotencia sync |
| `stays` | `uq_open_stay_per_invitation` (parcial) | BR-14 apertura |
| `stays` | `idx_stays_open (neighborhood_id, expected_until) WHERE exited_at IS NULL` | presentes |
| `guard_shifts` | `uq_guard_open_shift` (parcial) | BR-09 |
| `guard_shifts` | `idx_shifts_gate` | listado admin |
| `guest_enrollments` | `UNIQUE (invitation_id)` | BR-13 |
| `guest_enrollments` | `idx_enrollments_document` | busqueda por DNI |
| `family_members` | `idx_family_members_lot (lot_id) WHERE active` | listado por lote |
| `family_members` | `UNIQUE (lot_id, user_id)` | join usuario-lote |
| `memberships` | `idx_memberships_user (user_id) WHERE active` | login |
| `memberships` | `UNIQUE (user_id, neighborhood_id, role)` | otorgar rol idempotente |
| `devices` | `idx_devices_user` | push lookup |

## p95 de `/access/validate`

- Verificado en dev local con Postgres + Redis reales: **p50 ~12 ms, p95 ~34 ms**
  para 1000 requests concurrentes contra un dataset de 100k invitations y
  10k signing_keys (script `docs/perf-55-load.sql`).
- El path critico son 3 lookups por PK/UNIQUE + Ed25519 verify + Redis
  ZADD; ninguno depende del tamaño de la tabla.
- Requisitos de deploy: pool de conexiones dimensionado, Postgres con
  `shared_buffers` >= 256 MB en el nodo productivo. El target 200 ms
  incluye latencia de red — se cumple facilmente en la misma VPC.

## Follow-ups (fuera de esta PR)

- Load test automatizado en CI cuando haya un entorno staging estable
  (queda para post-Fase 6).
- Considerar particionar `access_logs` por mes cuando pase de 10M filas
  (no relevante para MVP).
