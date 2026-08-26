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

### 3. Seed de desarrollo

El endpoint de padron (WBS-11) todavia no existe, asi que los lotes y usuarios
iniciales se cargan por SQL. Flyway se encarga del esquema al arrancar la app;
el seed se corre despues, una vez que las migraciones esten aplicadas.

```powershell
# 1) Arrancar la app una vez para que Flyway aplique las migraciones.
#    (dejarla corriendo en otra terminal, o Ctrl+C despues del startup)
.\gradlew.bat bootRun

# 2) Cargar datos de prueba
docker exec -i securepassb-postgres psql -U securepass -d securepass < docs/seed.sql
```

El seed es idempotente (`ON CONFLICT DO NOTHING`), correlo cuantas veces quieras.

### 4. Arrancar el backend

```powershell
.\gradlew.bat bootRun
```

Escucha en `http://localhost:8080`. OpenAPI en `openapi.json`.

## Credenciales e IDs de dev

Todos los IDs estan fijos en `docs/seed.sql`.

| Rol            | Email             | Password    |
|----------------|-------------------|-------------|
| ADMIN          | admin@dev.local   | admin123    |
| GUARD          | guard@dev.local   | guard123    |
| RESIDENT_OWNER | owner@dev.local   | resident123 |

Credenciales de desarrollo local unicamente. El seed no se corre en
ningun entorno que no sea la maquina de un desarrollador. Para
staging o produccion, generar usuarios con passwords aleatorias y
nunca versionar el hash.

| Recurso        | UUID                                   |
|----------------|----------------------------------------|
| neighborhoodId | `11111111-1111-1111-1111-111111111111` |
| gateId         | `22222222-2222-2222-2222-222222222222` |
| lotId          | `33333333-3333-3333-3333-333333333333` |
| adminUserId    | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` |
| guardUserId    | `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb` |
| ownerUserId    | `cccccccc-cccc-cccc-cccc-cccccccccccc` |

Para emitir invitaciones vas a necesitar una `signing_keys` activa para el
barrio. No viene en el seed porque el cifrado depende de `SECUREPASS_MASTER_KEY`;
crearla via el endpoint admin correspondiente (o llamando a `SigningKeyIssuer`)
una vez que la app esta arriba.

## Comandos utiles

```powershell
.\gradlew.bat test              # suite completa (Testcontainers, no H2)
.\gradlew.bat check             # test + lint
docker compose down             # baja los contenedores, preserva el volumen
docker compose down -v          # ademas borra el volumen (reset total)
```
