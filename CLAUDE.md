# securepassb - Reglas para agentes

Backend de Securepass: control de acceso para barrios privados.
La fuente de verdad del proyecto es `spec.md`. Leelo antes de tocar codigo.

## Antes de escribir codigo

Primero entende el problema: lee el codigo que la tarea toca y trazá el flujo real.
Recien despues, frená en el primer escalon que aplique:

1. Esto necesita existir? Si no, no lo escribas.
2. Ya existe en este codebase? Reusalo.
3. Lo resuelve la stdlib de Java o Spring? Usalo.
4. Lo resuelve una dependencia ya instalada? Usala.
5. Entra en una linea? Una linea.
6. Recien ahi: lo minimo que funciona.

Perezoso con la solucion, nunca con la lectura.

**Nunca se recorta:** validacion en limites de confianza, manejo de errores,
seguridad, e integridad de la bitacora de accesos. Esas cuatro no entran en el
escalon 1 jamas.

## Stack

Java 21 · Spring Boot 3.3 · Gradle (Kotlin DSL) · PostgreSQL 16 · Redis 7 · Flyway.
Arquitectura hexagonal por modulo de dominio, detallada en `spec.md` seccion 3.1.

## Reglas duras

- `domain/` es Java puro. No importa Spring, JPA ni Jackson.
- `application/` define puertos (interfaces). `infrastructure/` los implementa.
- Los controllers no reciben ni devuelven entidades JPA. Solo `record` DTOs.
- Early returns. Prohibido anidar if/else. Guard clauses primero.
- `record` para DTOs y value objects. `sealed interface` para resultados cerrados.
- Sin `Optional` como parametro ni como campo.
- Constructor injection. Nunca `@Autowired` en campos.
- Las entidades de dominio validan sus invariantes en el constructor.
- Migraciones siempre por Flyway. Nunca `ddl-auto: update`.
- Sin secretos en el repo. Todo por variable de entorno.
- Cero emojis en codigo, nombres ni mensajes.

## Tests

Red-green-refactor. El test que falla se escribe primero.
Cobertura minima en CI: 80% en `domain/` y `application/`. El resto sin umbral.
Todo bugfix entra con un test que falla antes del fix.
Integracion con Testcontainers, no con H2: el trigger de inmutabilidad de
`access_logs` y los indices parciales no existen en H2.

## Flujo de trabajo

Un issue = una rama = un PR.

```
develop
  feature/<modulo>-<descripcion>
  fix/<modulo>-<descripcion>
  refactor/<modulo>-<descripcion>
```

Commits en Conventional Commits, en imperativo y minuscula:

```
feat(invitation): agregar firma ed25519 al token qr

Refs: #14
```

Scopes validos: `auth`, `invitation`, `access`, `shift`, `admin`, `db`.

Cada commit compila y pasa tests. Push al cerrar cada sesion de trabajo.

## Definition of Done

- [ ] Criterios de aceptacion del issue cumplidos
- [ ] Tests nuevos pasan y la suite completa esta en verde
- [ ] Sin warnings de compilador ni de lint
- [ ] Migracion Flyway incluida si cambio el esquema
- [ ] OpenAPI regenerado si cambio la API
- [ ] PR con `Closes #N`

## Que no hacer

- No implementar nada de la seccion 15 de `spec.md` (fuera de alcance).
- No agregar captura de patente. Esta decidido que queda para v2.
- No cambiar HMAC por Ed25519 al reves: la razon esta en `spec.md` seccion 5.1.
- No inventar endpoints que no esten en la seccion 7.
