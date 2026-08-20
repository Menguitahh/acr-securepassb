# Retomar sesion

Snapshot para reabrir el trabajo. Actualizado: 2026-08-20.

## Estado

Rama base: `develop`, limpia y al dia con `origin/develop`.

Spring Boot en 3.3.13.

Plan actual: cerrar el backend entero en 4 tandas (21 issues).

## Tandas

- **Tanda A - Desbloqueos**: WBS-11, 12, 21, 20. **CERRADA.**
- **Tanda B - Adenda v1.1**: WBS-59, 60, 61, 65, 66, 67, 70, 71. En curso.
- **Tanda C - Operacion de garita**: WBS-26, 27, 28, 9.
- **Tanda D - Endurecimiento**: WBS-54, 55, 56, 57.

Fuera de alcance: Fase 6 (dashboard admin web, D-03).

## Issues cerrados en esta sesion

- [x] WBS-11 · Gestion de lotes (#69, `5622006`)
- [x] WBS-12 · Permisos de unidad familiar (#70, `26a3a6b`)
- [x] WBS-21 · Pase personal del residente (#71, `407aaa1`)
- [x] WBS-20 · Materializar EXPIRED con job programado (#72, `16e091b`)
- [x] WBS-59 · DNI obligatorio en invitacion (#68) — cerro rama colgada
- [x] WBS-60 · Dominio y API de enrolamiento por DNI (#73, `389971e`)
- [x] WBS-61 · NEEDS_ENROLLMENT en validacion (#74, `7aa05a3`, fix #75)
- [x] WBS-65 · Dominio de estadias con hook por acceso (#76, `fba9204`)
- [x] WBS-66 · Endpoint de presentes por barrio (#77, `62b9603`)

Tambien: `chore: agregar seed de desarrollo y documentacion de setup`
(`022f042`) y `docs(spec): agregar 7.6 unidad familiar`.

## Que sigue

Proximo: **WBS-67 Cierre manual de estadia** (`securepassb`, rama
`feature/stay-manual-close`).

Criterios:
- `POST /api/v1/stays/{id}/close` (rol GUARD).
- Marca `closed_manually = TRUE` y `closed_by = guardId`.
- Append-only respetado: no toca `access_logs`, solo `stays`.
- Rechaza cerrar una stay ya cerrada.

Notas para arrancar:
- El dominio `Stay.close(exitLogId, at, manually, by)` exige `exitLogId`
  no nulo. Un cierre manual no tiene egreso registrado. Ver si conviene
  relajar el invariante (permitir `exitLogId` nullable) o registrar un
  `access_log` sintetico.
- Ponytail: el schema V4 permite `exit_log_id` NULL; alinear el domain
  con eso es el camino corto.

Despues de WBS-67 vienen 70 (aviso al residente) y 71 (extension de
invitacion) para cerrar Tanda B.

## Pausas obligatorias

- **WBS-70**: revisar antes de mergear (aviso al residente, cruce
  push + notificacion).
- **WBS-54** (Tanda D): checklist de seguridad item por item.

## Reglas de sesion (vigentes)

1. No explicar lo que voy a hacer, hacerlo.
2. No resumir despues, el diff habla.
3. Nada de entrevistas ni skills de grilling; el spec ya define todo.
4. Maximo 2 intentos por problema; al tercero, parar y preguntar.
5. Leer solo los archivos que la tarea toca.
6. Tests solo en `domain/` y `application/` (excepciones justificadas:
   snapshot OpenAPI, integration para cascadas cross-modulo).
7. Si voy a apartarme de algo que el spec define, preguntar antes.
8. Al terminar cada issue, working tree limpio y parado en `develop`.
   Nada de ramas con cambios sin commitear.
9. Al cerrar cada tanda: regenerar OpenAPI, listar cambios de API que
   consume el frontend, y avisar para que el usuario pruebe.
10. Cualquier cambio de payload/respuesta que consuma el frontend se
    avisa explicito en el cierre del issue.

Extras acordados en esta sesion:
- Solo un actor (agente/editor) tocando el working tree por vez. El
  frontend se aparto a `securepassf` y no vuelve a tocar este repo.
- Credenciales de dev en `docs/seed.sql` y `README.md` estan en claro.
  Repo privado, solo para maquinas de desarrollador; en staging/prod
  se generan passwords aleatorias.
- Cambios de contrato con el frontend: pedir el shape si el frontend
  tiene una expectativa concreta; si no, decidir aca y avisar.

## Comandos utiles

- Regenerar snapshot OpenAPI: `OPENAPI_UPDATE=true ./gradlew test`.
- Correr suite completa: `./gradlew test`.
- Build + tests: `./gradlew build`.
- Env vars requeridas para correr la app localmente: ver `.env` o
  `README.md`.
