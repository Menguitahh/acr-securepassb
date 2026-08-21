# Auditoria de seguridad — spec 5.5

Verificacion item por item del checklist transversal (WBS-54).
Fecha: 2026-08-21.

## 1. Aislamiento por barrio (IDOR)

**Estado:** verificado.

- Cada use case que toca recursos del dominio recibe el `neighborhoodId`
  del actor (via `AccessGuard.require()` -> `CurrentUser.neighborhoodId()`)
  y compara contra el `neighborhoodId` del recurso persistido. Los
  mismatches devuelven `ResourceNotFoundException` (404) para no filtrar
  existencia; los mismatches de scope de lote devuelven `AccessDeniedException` (403).
- Los queries JDBC de lectura masiva (listados, bitacora, presentes)
  incluyen `WHERE neighborhood_id = :nid` como filtro obligatorio.
- Test transversal nuevo: `src/test/java/com/securepass/security/IdorIsolationTest.java`.
  Levanta dos barrios en un Postgres real (Testcontainers) y verifica que
  un actor del barrio A no puede: (a) suspender un lote de B via
  `UpdateLotUseCase`, (b) cerrar una `stay` de B via `CloseStayManuallyUseCase`,
  (c) leer `access_logs` de lotes ajenos via `ListAccessLogsUseCase`.
- Tests por use case (existentes) que ya cubren isolacion cross-barrio:
  `UpdateLotUseCaseTest`, `AddLotMemberUseCaseTest`, `CloseStayManuallyUseCaseTest`,
  `NotifyResidentOfStayUseCaseTest`, `EnrollGuestUseCaseTest`,
  `ListOpenStaysUseCaseTest`, `ListAccessLogsUseCaseTest`.

## 2. @PreAuthorize explicito en cada endpoint

**Estado:** completado en esta PR.

Se auditaron los 9 `@RestController`. Antes de esta PR faltaba
`@PreAuthorize` en:

- `AccessLogsController#list`
- `FamilyController#list`, `#add`, `#update`
- `InvitationController#create`, `#list`, `#detail`, `#extend`, `#revoke`
- `MePassesController#passes`
- `AuthController#login`, `#refresh`, `#logout`

Corregidos. Roles asignados segun spec 7. `AuthController` usa
`permitAll()`/`isAuthenticated()` explicito (no queda a merced del
`.requestMatchers(...)` que ya los declara `permitAll` en el filter
chain: la anotacion explicita evita defaults permisivos si alguien
mueve las rutas).

## 3. Sin secretos en el repo

**Estado:** verificado.

- `application.yml` lee todos los valores de entorno; defaults solo
  para dev local (DB user/password, host de Postgres/Redis). Los
  secretos sensibles (`SECUREPASS_JWT_SECRET`, `SECUREPASS_MASTER_KEY`)
  **no tienen default** y la app no levanta sin ellos.
- El seed de dev en `docs/seed.sql` documenta credenciales locales
  (repo privado, solo maquinas de desarrollador; ver `README.md`).
- Ningun `password_hash` productivo ni token en el repo.

## 4. Clave privada Ed25519 cifrada con SECUREPASS_MASTER_KEY (AES-GCM)

**Estado:** verificado, sin cambios.

- `signing_keys.private_key_enc` guarda la clave privada cifrada.
- `AesGcmCipher` cifra con AES-256-GCM, IV random por operacion
  (`shared/crypto/AesGcmCipher.java`).
- La master key se lee de `SECUREPASS_MASTER_KEY` (`shared/crypto/MasterKey.java`).
- Test existente `Ed25519EncryptionTest` verifica el roundtrip.

## 5. CORS restringido al dominio del admin web

**Estado:** completado en esta PR.

- `SecurityConfig.corsConfigurationSource()` restringe origenes
  a `SECUREPASS_CORS_ALLOWED_ORIGINS` (csv, default
  `https://admin.securepass.app`).
- Metodos permitidos: `GET, POST, PATCH, DELETE, OPTIONS`.
- Headers permitidos: `Authorization`, `Content-Type`, `X-Device-Id`.
- `Retry-After` expuesto para que el cliente pueda leer el header en
  respuestas 429.
- Solo se aplica bajo `/api/**`. Actuator y swagger fuera de CORS.

## 6. Logs sin PII

**Estado:** revisado y corregido.

- Los `LOG.info/warn/error` de la aplicacion no imprimen tokens
  completos, documentos ni telefonos.
- Un log en `LoggingPushSender` imprimia `subjectName` del invitado.
  Se saco y quedo solo `stayId` + `residentUserId` como
  identificadores opacos. Nombres se consideran PII para MVP.
- Los `payload.toString()` de `QrTokenPayload` no incluyen documento
  (el DNI nunca entra al payload del QR — BR-11).
- Recordatorio operativo: nunca activar `logging.level=DEBUG`
  sobre `org.springframework.jdbc.core.JdbcTemplate` en produccion:
  loguea parametros bindeados y entre ellos hashes de refresh y
  documentos escaneados. Configurable a nivel entorno.

## 7. HTTPS obligatorio; HSTS en admin web

**Estado:** responsabilidad del deploy, documentada.

- El backend no fuerza HTTPS a nivel aplicacion: se termina TLS en el
  reverse proxy / load balancer (nginx / Cloud Run / ALB).
- HSTS se emite desde el proxy que sirve el admin web (Fase 6, fuera
  de alcance del backend actual).
- Recordatorio para el deploy:
  - Ingress con `Strict-Transport-Security: max-age=31536000; includeSubDomains`.
  - Redirect 301 de `http://` a `https://` en el proxy.
  - Sin fallback plaintext en las variables `DB_URL` productivas
    (usar `sslmode=require`).

## Follow-ups no bloqueantes

- IDOR test corre contra `ListAccessLogsUseCase` y valida solo el
  lado de use case + jdbc. Un test end-to-end MockMvc con dos JWTs
  distintos cerraria el bucle del filter chain -> sumar cuando este
  el harness de MockMvc.
- Agregar test que verifique el bloqueo CORS con `Origin` no
  permitido. Requiere levantar el filter chain en un test de
  integracion; queda para WBS-56.
