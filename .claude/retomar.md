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

## Que sigue

Proximo: **WBS-17 Dominio invitacion** (`securepassb#15`, rama
`feature/invitation-domain`). Cubre spec 6.

Criterios del issue:
- Maquina de estados con transiciones prohibidas testeadas.
- BR-04 (ventana <= 30 dias SINGLE/EVENT, <= 365 RECURRING) cubierta.
- BR-05 (max_uses EVENT <= 200) cubierta.
- BR-06 (used_count solo incrementa en IN + ALLOWED) cubierta.
- BR-10 (QR personal de residente, no consume cupo) cubierta.

Ya existe `InvitationType` enum en `modules/invitation/domain` (agregado
en WBS-16). WBS-17 agrega la entidad `Invitation` con invariantes en el
constructor y transiciones sealed.

Despues siguen: 18, 19, 22 (pausa antes de mergear), 23 (pausa antes de
mergear), 24, 25.

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
