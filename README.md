# securepassb

Backend de Securepass. Control de acceso para barrios privados.
Fuente de verdad funcional: `spec.md`. Reglas para agentes: `CLAUDE.md`.

## Requisitos

- JDK 21
- Docker Desktop (Postgres 16 + Redis 7 via compose)
- PowerShell 7 (Windows) o bash

## Levantar desde cero

### 1. Infraestructura

```powershell
docker compose up -d
```

Levanta `securepassb-postgres` (5432) y `securepassb-redis` (6379).
Volumen `postgres-data` persiste entre reinicios.

### 2. Variables de entorno

El repo trae `.env` con placeholders **solo aptos para dev local**.
No tiene secretos reales y NO se commitea si lo modificas.

Cargar en la sesion de PowerShell antes de `bootRun`:

```powershell
Get-Content .env | Where-Object { $_ -match '^\s*[^#].*=' } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name.Trim(), $value.Trim(), 'Process')
}
```

En bash:

```bash
set -a; source .env; set +a
```

Para generar valores reales:

```powershell
# SECUREPASS_MASTER_KEY (32 bytes base64)
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 } | ForEach-Object { [byte]$_ }))
# SECUREPASS_JWT_SECRET (48 bytes base64)
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Max 256 } | ForEach-Object { [byte]$_ }))
```

### 3. Arrancar el backend con seed dev

El seed corre dentro de la app (bean `DevSeedRunner`) cuando estan activadas
las dos barreras:

1. Perfil Spring `dev` activo.
2. Property `securepass.dev.seed=true` (default `false`).

Con las dos: al arrancar, el runner llama al mismo `NeighborhoodProvisioner`
que usa `./gradlew provisionNeighborhood` para crear el barrio + garita + admin
+ signing key, con los UUIDs fijos de siempre, y luego aplica un overlay SQL
con lo que es dev-only (guard, owner, lote, permisos, activacion del admin).

Idempotente: si el barrio dev ya existe, no hace nada.

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SECUREPASS_DEV_SEED = "true"
.\gradlew.bat bootRun
```

En bash:

```bash
SPRING_PROFILES_ACTIVE=dev SECUREPASS_DEV_SEED=true ./gradlew bootRun
```

Sin la property, la app arranca vacia (util para probar el flujo de
`provisionNeighborhood` a mano). Sin el perfil `dev`, la property se ignora.

Escucha en `http://localhost:8080`. OpenAPI en `openapi.json`.

## Demo para presentar

`DemoSeedRunner` arma un ejemplo completo para mostrar el sistema. Usa las mismas barreras: perfil `dev` + `SECUREPASS_DEMO_SEED=true`. Es idempotente: si el barrio "Los Pinos" ya existe, no hace nada.

```powershell
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SECUREPASS_DEMO_SEED = "true"
.\gradlew.bat bootRun
```

Que crea:

- **Duenio de la plataforma**: ve los dos barrios desde `/plataforma` en el web.
- **Los Pinos** (Mendoza), con todas sus cuentas:
  - 12 lotes; B-06 suspendido por mora;
  - titulares, una familiar, un jardinero (lunes a viernes de 8 a 17) y el guardia;
  - una semana de movimiento: turnos, ingresos y egresos, denegados (pase vencido, revocado, lote en mora, trabajador fuera de horario);
  - dos visitas adentro hoy, una ya pasada de su horario;
  - 3 invitaciones vigentes para la demo en vivo (Sofia Perez y Diego Torres en A-03, Pablo Rios en B-02).
- **Altos del Sol**: 8 lotes y su admin pendiente de activacion. Solo para verlo desde la plataforma; no tiene cuentas para usar.

El guardia no tiene turno abierto: lo abre en vivo desde la app.

| Rol | Email | Password |
|---|---|---|
| Duenio de la plataforma | plataforma@securepass.example | Plataforma2026 |
| Admin de Los Pinos (web) | admin@lospinos.example | LosPinos2026 |
| Guardia (app) | guardia@lospinos.example | LosPinos2026 |
| Titular A-03 (app) | titular@lospinos.example | LosPinos2026 |
| Familiar A-03 (app) | familiar@lospinos.example | LosPinos2026 |
| Trabajador A-03 (app) | trabajador@lospinos.example | LosPinos2026 |

**Antes de presentar, reiniciar la demo.** La semana de movimiento y las invitaciones se arman relativas al momento en que corre el seed: si se presenta otro dia, "hoy" queda vacio y las invitaciones vencidas. `access_logs` no se puede borrar (auditoria), asi que el reinicio recrea la base de dev entera. **Borra todo lo que haya en la base local**; Barrio Dev se vuelve a sembrar solo.

```powershell
docker compose down -v
docker compose up -d
$env:SPRING_PROFILES_ACTIVE = "dev"
$env:SECUREPASS_DEV_SEED = "true"
$env:SECUREPASS_DEMO_SEED = "true"
.\gradlew.bat bootRun
```

Esperar en el log la linea `Demo sembrada` antes de loguearse: el server responde unos segundos antes de que termine el seed.

Los mails de activacion de estas cuentas solo van al log (`SECUREPASS_EMAIL_PROVIDER` en `logging`). Los dominios `.example` estan reservados y no llegan a nadie.

## Credenciales e IDs de dev

Todos los IDs estan fijos en `DevSeedRunner` + `src/main/resources/dev/dev-overlay.sql`.

| Rol            | Email             | Password       |
|----------------|-------------------|----------------|
| ADMIN          | admin@dev.local   | admin123       |
| GUARD          | guard@dev.local   | guard123       |
| RESIDENT_OWNER | owner@dev.local   | resident123    |
| RESIDENT_OWNER | owner@test.com    | OwnerPass123!  |
| RESIDENT_MEMBER| family@test.com   | FamilyPass123! |
| WORKER         | worker@test.com   | WorkerPass123! |

Credenciales de desarrollo local unicamente. El seed no se corre en
ningun entorno que no sea la maquina de un desarrollador. Para
staging o produccion, generar usuarios con passwords aleatorias y
nunca versionar el hash.

| Recurso        | UUID                                   |
|----------------|----------------------------------------|
| neighborhoodId | `11111111-1111-1111-1111-111111111111` |
| gateId         | `22222222-2222-2222-2222-222222222222` |
| lotId          | `33333333-3333-3333-3333-333333333333` |
| lotIdA14       | `44444444-4444-4444-4444-444444444444` |
| lotIdA15       | `55555555-5555-5555-5555-555555555555` |
| adminUserId    | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` |
| guardUserId    | `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` |
| ownerUserId    | `cccccccc-cccc-cccc-cccc-cccccccccccc` |
| workerUserId   | `d0000005-0000-0000-0000-000000000000` |

Para emitir invitaciones vas a necesitar una `signing_keys` activa para el
barrio. No viene en el seed porque el cifrado depende de `SECUREPASS_MASTER_KEY`;
crearla via el endpoint admin correspondiente (o llamando a `SigningKeyIssuer`)
una vez que la app esta arriba.

## Correr todo en Docker

```powershell
Copy-Item .env.example .env
docker compose --profile app up -d --build
curl.exe http://localhost:8080/actuator/health
```

Detalle de la imagen, Compose y comandos: `docs/docker.md`.

## Azure

Backend desplegado: https://securepassb.proudbush-b955ebd5.eastus.azurecontainerapps.io
(`/actuator/health`, `/actuator/info`). Operacion manual: `python scripts/azure.py --help`.
Infraestructura, secretos y despliegue: `docs/azure.md`. CI/CD: `docs/ci-cd.md`. Monitoreo: `docs/monitoreo.md`.
Seguridad: `docs/seguridad.md`, `docs/vulnerabilidades.md`.
Guia para la defensa (estado de requisitos, simulacro, 50 preguntas): `docs/guia-parcial.md`.

## Comandos utiles

```powershell
.\gradlew.bat test              # suite completa (Testcontainers, no H2)
.\gradlew.bat check             # test + lint
docker compose down             # baja los contenedores, preserva el volumen
docker compose down -v          # ademas borra el volumen (reset total)
```
