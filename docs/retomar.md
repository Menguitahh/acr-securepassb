# Retomar sesion

Snapshot para reabrir el trabajo. Actualizado: 2026-08-19.

## Estado

Rama base: `develop`, limpia y al dia con `origin/develop`.

Spring Boot en 3.3.13 (verificado al empezar la sesion, no en 4).

## Issues cerrados

Orden pedido: 6, 7, 8, 10, 13, 14, 15, 16, 17, 18, 19, 22, 23, 24, 25.

- [x] WBS-6 · Dominio de identidad (#40)
- [x] WBS-7 · Login y JWT HS256 + BCrypt cost 12 (#41)
- [x] WBS-8 · Refresh con rotacion + logout, familia en Redis (#42)
- [x] WBS-10 · Autorizacion por rol y barrio, JWT filter + AccessGuard (#43)
- [x] WBS-13 · Handler global application/problem+json (#44)
- [x] WBS-14 · Contratos OpenAPI + snapshot `openapi.json` en CI (#45)
- [x] WBS-15 · Cripto Ed25519, AES-GCM con IV random, master key en env (#46)
- [x] WBS-16 · Emision de token QR SP1 con firma Ed25519 (#47)
- [x] WBS-17 · Dominio Invitation con maquina de estados (#49)
- [x] WBS-18 · API de invitaciones CRUD + revoke (#50)
- [x] WBS-19 · Revocacion: jti estable + `/access/revoked` (#51)
- [x] WBS-22 · Caso de uso de validacion + AccessDecision sealed (#52)
- [x] WBS-23 · Anti-replay Redis + consumo transaccional (#53)
- [x] WBS-24 · Endpoint POST /access/validate con log e idempotencia (#54)

## Que sigue

Proximo: **WBS-25 Sync de eventos offline** (`securepassb`, rama
`feature/access-offline-sync`). Cubre US-19 y spec 8.4.

Criterios del issue:
- `POST /access/sync` idempotente por `clientEventId`.
- Batch de max 50 eventos.
- Conflictos resueltos first-captured-wins (usa `captured_at`).
- Duplicados persistidos como `REPLAY` en `access_logs`.
- Desvio de reloj > 5 min se marca en auditoria.

Notas para arrancar:
- `RecordAccessAttemptUseCase` ya maneja idempotencia por
  `clientEventId` via `saveIdempotent`. Reusar.
- `ConsumeAccessUseCase` + anti-replay Redis ya existen; el sync
  itera eventos y los pasa por el mismo pipeline.
- La respuesta del sync es `{ accepted, conflicts[] }` (spec 7.3).
- Este es el ULTIMO issue antes del `/ponytail-review` acumulado.

Recordatorio del usuario: `/ponytail-review` NO se corre por issue en
esta tanda. Los tres ultimos dieron solo falsos positivos sobre
dominio que el spec define. Retomarlo recien tras cerrar WBS-25,
sobre el acumulado completo.

Follow-ups pendientes (no urgentes):
- `lot.ownerName` en la respuesta ALLOWED de `/access/validate`.
- Load test de p95 < 200 ms para `/access/validate` (WBS-24).

## Reglas de sesion (mantener vigentes)

1. No explicar lo que voy a hacer, hacerlo.
2. No resumir despues, el diff habla.
3. Nada de entrevistas ni `/grill-with-docs`, el spec ya define todo.
4. Maximo 2 intentos por problema; al tercero, parar y preguntar.
5. Leer solo los archivos que la tarea toca.
6. Tests solo en `domain/` y `application/`. En adaptadores no
   (excepciones justificadas: snapshot OpenAPI, leak test de la privada).
7. Si voy a apartarme de algo que el spec define, preguntar antes.

Extras acordados durante la sesion:
- `/ponytail-review` no corre por issue. Se dispara cada 4 issues
  cerrados sobre el acumulado.
- Si el review propone borrar codigo del dominio que el spec define
  explicitamente (por falta de consumidores todavia), ignorar y seguir.
- Parar y avisar antes de mergear en WBS-15, 16, 22, 23. Los otros van
  directo: rama desde develop -> implementar -> commit convencional con
  `WBS-N` y `Refs: #<pr>` -> push -> PR contra develop -> merge squash.

## Comandos utiles

- Regenerar snapshot OpenAPI: `OPENAPI_UPDATE=true ./gradlew test`.
- Correr suite completa: `./gradlew test`.
- Build + tests: `./gradlew build`.
- Env vars requeridas para correr la app localmente: ver `.env.example`.
