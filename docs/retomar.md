# Retomar sesion

Snapshot para reabrir el trabajo. Actualizado: 2026-08-21.

## Estado

Rama base: `develop`, limpia y al dia con `origin/develop`.

Spring Boot en 3.3.13.

Plan: cerrar el backend entero en 4 tandas (21 issues). **BACKEND CERRADO.**

## Tandas

- **Tanda A - Desbloqueos**: WBS-11, 12, 21, 20. **CERRADA.**
- **Tanda B - Adenda v1.1**: WBS-59, 60, 61, 65, 66, 67, 70, 71. **CERRADA.**
- **Tanda C - Operacion de garita**: WBS-26, 27, 28, 9. **CERRADA.**
- **Tanda D - Endurecimiento**: WBS-54, 55, 56, 57. **CERRADA.**

Fuera de alcance: Fase 6 (dashboard admin web, D-03).

## Issues cerrados hasta ahora en la sesion

**Tanda A** (todos en develop):
- WBS-11 · Gestion de lotes (#69)
- WBS-12 · Permisos de unidad familiar (#70)
- WBS-21 · Pase personal del residente (#71)
- WBS-20 · Materializar EXPIRED con job programado (#72)

**Tanda B**:
- WBS-59 · DNI obligatorio en invitacion (#68)
- WBS-60 · Dominio y API de enrolamiento por DNI (#73)
- WBS-61 · NEEDS_ENROLLMENT en validacion (#74, fix #75)
- WBS-65 · Dominio de estadias con hook por acceso (#76)
- WBS-66 · Endpoint de presentes por barrio (#77)
- WBS-67 · Cierre manual de estadia por el guardia (#78)
- WBS-70 · Aviso al residente desde estadia abierta (#79)
- WBS-71 · Extension de invitacion por politica del barrio (#80)

**Tanda C**:
- WBS-27 · Turnos de guardia (#81) — se hizo antes de 26 por dependencia
- WBS-26 · Ingreso manual sin QR con turno abierto (#82)
- WBS-28 · Bitacora consultable con filtros y aislamiento (#83)
- WBS-9 · Rate limiting con Redis sliding window (#84)

**Tanda D**:
- WBS-54 · Auditoria de seguridad + IDOR test (#85)
- WBS-55 · EXPLAIN ANALYZE + inventario de indices (#86, doc-only)
- WBS-56 · Prueba de campo offline: 200 escaneos + sync (#88, test)
- WBS-57 · Observabilidad: metricas Prometheus + logs JSON (#87)

## Que sigue

Backend cerrado. Frontend es la siguiente pieza.

## Pausas obligatorias

- **WBS-70**: ya cumplida y mergeada.
- **WBS-54**: ya cumplida y mergeada tras dos rondas de fixes
  (rate limit por stay, drop residentUserId, cerrar 3 IDOR holes en
  ManualAccess/CreateInvitation/ExtendInvitation, ampliar test IDOR
  de 3 a 14 rutas).

## Cambios de contrato acumulados que consume el frontend

Regenerar el cliente OpenAPI. Endpoints nuevos desde el ultimo update:

**Admin / padron**
- `GET/POST /api/v1/admin/lots`, `PATCH /api/v1/admin/lots/{id}`
- `GET/POST /api/v1/admin/lots/{id}/members`

**Unidad familiar (titular)**
- `GET /api/v1/family/lots/{lotId}/members`
- `POST /api/v1/family/lots/{lotId}/members`
- `PATCH /api/v1/family/lots/{lotId}/members/{userId}`

**Residente**
- `GET /api/v1/me/passes`
- `POST /api/v1/invitations/{id}/extend`

**Garita**
- `POST /api/v1/access/enroll` (body lleva `token`, no `invitationId`)
- `POST /api/v1/access/manual`
- `POST /api/v1/shifts/open`, `POST /api/v1/shifts/close`,
  `GET /api/v1/shifts/current`, `GET /api/v1/shifts?...`
- `GET /api/v1/stays/open`
- `POST /api/v1/stays/{id}/close`
- `POST /api/v1/stays/{id}/notify-resident`
- `GET /api/v1/access-logs?from=&to=&lotId=&result=&page=`

**Comportamiento nuevo en endpoints existentes**
- `POST /api/v1/invitations`: 422 con `rule="BR-03"` si el lote no
  esta `ACTIVE`.
- `POST /api/v1/access/validate`: puede devolver `NEEDS_ENROLLMENT`
  con `{ result, invitationId, lot: {code} }` (sin `guest`). Frontend
  usa el `invitationId` para el flujo de scan de DNI + `/access/enroll`
  con un `clientEventId` NUEVO.
- `POST /api/v1/access/validate`: si el `qrToken` es un residentPass
  y el lote esta `SUSPENDED`, devuelve `ALLOWED` (US-08 excepcion).
- Rate limits activos en `/auth/login` (5/15min por IP+email),
  `/invitations` (30/hora), `/access/validate` (120/min por
  `X-Device-Id` o IP), `/access/sync` (10/min por dispositivo).
  Al 429 el header `Retry-After` indica los segundos.

## Reglas de sesion (vigentes)

1. No explicar lo que voy a hacer, hacerlo.
2. No resumir despues, el diff habla.
3. Nada de entrevistas ni skills de grilling; el spec ya define todo.
4. Maximo 2 intentos por problema; al tercero, parar y preguntar.
5. Leer solo los archivos que la tarea toca.
6. Tests solo en `domain/` y `application/` (excepciones justificadas:
   snapshot OpenAPI, integration para cascadas cross-modulo, IDOR).
7. Si voy a apartarme de algo que el spec define, preguntar antes.
8. Al terminar cada issue, working tree limpio y parado en `develop`.
9. Al cerrar cada tanda: regenerar OpenAPI, listar cambios de API y
   avisar para que el usuario pruebe. En esta sesion el usuario
   pidio explicitamente NO parar entre tandas B/C/D salvo pausas.
10. Cambios de payload/respuesta que consuma el frontend se avisan
    en el cierre del issue.

## Comandos utiles

- Regenerar snapshot OpenAPI: `OPENAPI_UPDATE=true ./gradlew test`.
- Correr suite completa: `./gradlew test`.
- Env vars requeridas para correr la app: ver `.env` o `README.md`.
- Nuevas env vars introducidas en esta sesion:
  - `SECUREPASS_CORS_ALLOWED_ORIGINS` (csv, default `https://admin.securepass.app`)
  - `SECUREPASS_INVITATION_EXPIRY_CRON` (default cada 15 min)
