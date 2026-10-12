# Azure

Infraestructura de `securepassb` en Microsoft Azure: que recursos usa, por
que, cuanto cuestan y como se crean, verifican y eliminan.

> Los valores reales de IDs y secretos no se escriben aca. Se muestran
> abreviados (`bf8523b9...`) o como placeholders (`<NOMBRE>`).

## Jerarquia de recursos

```mermaid
flowchart TD
    T[Tenant Microsoft Entra ID<br/>identidades: usuario y Service Principal] --> S
    S[Suscripcion<br/>Azure for Students] --> RG[Resource Group<br/>universidad]
    RG --> ACR[Azure Container Registry<br/>acringenieriaum]
    RG --> ENV[Container Apps Environment<br/>managedEnvironment-universidad-8a60]
    RG --> LAW[Log Analytics<br/>workspaceuniversidadaf47]
    RG --> PG[(PostgreSQL Flexible Server<br/>psql-securepassb-um, centralus)]
    ACR -->|imagen securepassb:tag| APP
    ENV --> APP[Container App securepassb]
    APP --> BE[contenedor backend<br/>Spring Boot :8080]
    APP --> RD[contenedor redis<br/>sidecar :6379]
    BE -->|JDBC + TLS| PG
    BE -->|localhost:6379| RD
    ENV -->|logs de consola| LAW
```

- **Suscripcion**: el contrato de facturacion. Todo recurso vive en una.
- **Resource Group**: carpeta logica dentro de la suscripcion. Permisos,
  costos y borrado se pueden manejar por grupo.
- **ACR**: registro privado de imagenes Docker (como Docker Hub, pero
  nuestro). El CI publica ahi; Container Apps descarga de ahi.
- **Container Apps Environment**: el "cluster" administrado donde corren las
  Container Apps; comparte red y destino de logs.
- **Container App**: la aplicacion en si: imagen, CPU/memoria, variables,
  secretos, ingreso HTTPS, escalado y revisiones.

## Relevamiento de lo existente (2026-10-11, solo lectura)

| Recurso | Nombre | Estado | Decision |
|---|---|---|---|
| Suscripcion | Azure for Students (`bf8523b9...`) | Enabled. Sin tarjeta: al agotar el credito los recursos se deshabilitan, no se cobra | Usar |
| Resource Group | `universidad` (eastus) | Existe | Reutilizar |
| ACR | `acringenieriaum.azurecr.io` | SKU Standard, admin habilitado, 9,5 MB usados de 100 GB. Repos: `app-azure`, `1` | Reutilizar, repositorio nuevo `securepassb`. Bajado a Basic (ver Ahorros) |
| Container Apps Environment | `managedEnvironment-universidad-8a60` | Perfil Consumption, sin VNet, logs a Log Analytics, dominio `proudbush-b955ebd5.eastus.azurecontainerapps.io` | Reutilizar |
| Container App | `app-azure` (Go de la cursada) | Stopped (no consume) | No se toca |
| Log Analytics | `workspaceuniversidadaf47` | PerGB2018, retencion 30 dias, **sin tope diario** | Reutilizar, con tope diario (ver Ahorros) |
| PostgreSQL | `psql-securepassb-um` | Creado en la Fase 7 (ver abajo) | Usar |
| Redis | - | No existe | Sidecar dentro de la Container App |
| Key Vault | - | No existe, proveedor no registrado | No se usa (ver abajo) |
| Service Principal | appId `542811c2...` | `Contributor` sobre `universidad`, `Reader` sobre la suscripcion | Reutilizar (ver Service Principal) |
| Identidad administrada | `id-securepassb` (eastus) | Creada en la Fase 8, gratis | La Container App la usa para bajar imagenes |

Proveedores de recursos de la suscripcion: `Microsoft.App`,
`Microsoft.ContainerRegistry` y `Microsoft.OperationalInsights` registrados;
`Microsoft.DBforPostgreSQL` no estaba registrado: lo registro el duenio de la suscripcion (paso manual 2).

### Hallazgo: Postgres restringido en eastus y eastus2

```
az postgres flexible-server list-skus -l eastus2
"reason": "Subscriptions are restricted from provisioning in this region..."
```

Azure for Students bloquea Postgres en algunas regiones. Ojo: con el
proveedor `Microsoft.DBforPostgreSQL` sin registrar, la consulta devuelve
datos incompletos y una region bloqueada parece libre. Ya registrado, el
relevamiento real fue:

| Region | Postgres B1ms + PG 16 |
|---|---|
| eastus, eastus2, southcentralus, westus2, canadaeast | Bloqueada |
| **centralus**, northcentralus, westcentralus, westus, westus3, canadacentral, brazilsouth | Disponible |

Se eligio **`centralus`**: la mas cercana al environment de Container Apps
(`eastus`), ~20-25 ms de latencia. El costo no cambia con la region. Si la
latencia molestara, se puede crear un environment de Container Apps en
`centralus` (gratis) y mover la app.

## Recursos a crear y costo

Precios aproximados en USD, region Central US. Todo sale del credito de
Azure for Students; ninguno cobra plata real.

| Recurso | Para que | Hace falta? | Costo | Como no gastar | Como eliminar |
|---|---|---|---|---|---|
| PostgreSQL Flexible Server B1ms, 32 GB, PG 16 | Base de datos de produccion | Si: el backend no arranca sin base | Gratis 12 meses dentro del cupo de servicios gratuitos (750 h/mes de B1ms + 32 GB), confirmado en Free services. Fuera del cupo ~13 USD/mes de computo + ~4 USD/mes de disco | Elegir exactamente B1ms y 32 GB, sin alta disponibilidad, sin backup geo-redundante. Se puede detener (`az postgres flexible-server stop`) hasta 7 dias | `az postgres flexible-server delete -g universidad -n <NOMBRE_PG>` (borra los datos) |
| Container App `securepassb` (backend + Redis sidecar) | Correr el backend | Si | Cupo gratis mensual por suscripcion: 180.000 vCPU-s, 360.000 GiB-s, 2 M requests. Con escala a cero el uso normal entra en el cupo. Encendida 24/7 con 0,75 vCPU / 1,5 GiB ~15 USD/mes | `min-replicas 0`; subir a 1 solo para la demo | `az containerapp delete -g universidad -n securepassb` |
| Repositorio `securepassb` en el ACR | Guardar las imagenes versionadas | Si | Incluido en el SKU del ACR | Borrar tags viejos | `az acr repository delete -n acringenieriaum --repository securepassb` |

### Recursos existentes que hoy gastan credito

| Recurso | Costo | Recomendacion |
|---|---|---|
| ACR **Standard** | ~0,67 USD/dia (~20 USD/mes) aunque no se use | Bajar a **Basic** (~5 USD/mes): 10 GB alcanzan (hoy hay 9,5 MB). Reversible: `az acr update -n acringenieriaum --sku Standard` |
| Log Analytics sin tope | Los primeros 5 GB/mes son gratis; despues ~2,3 USD/GB | Tope diario de 0,15 GB (~4,5 GB/mes): si se alcanza, se deja de ingerir hasta el dia siguiente |

### Por que Redis va como sidecar

Un sidecar es un segundo contenedor dentro de la misma Container App. El
backend lo ve en `localhost:6379`, sin red ni ingreso TCP que configurar, y
no es un recurso aparte: no suma costo fijo. Azure Cache for Redis costaria
~16 USD/mes.

Limites aceptados:
- Redis no tiene disco: si la app escala a cero o se reinicia, se pierden los
  refresh tokens (los usuarios vuelven a loguearse) y los contadores de rate
  limit. No se pierden datos de negocio: esos estan en Postgres.
- `max-replicas 1`: con dos replicas cada una tendria su propio Redis y no
  compartirian sesiones. Para un TP alcanza; para escalar, Redis pasa a ser
  un servicio aparte.

### Por que no Key Vault (por ahora)

Los secretos de una Container App ya se guardan cifrados y se exponen al
contenedor como variables con `secretref:`. Key Vault agrega rotacion
centralizada y auditoria, pero requiere registrar el proveedor y asignar un
rol a la identidad de la app, cosa que el Service Principal no puede hacer
(es `Contributor`, no `Owner`). Queda como mejora.

## Pasos manuales

Los pasos 1 y 2 los hace el **duenio de la suscripcion** con su usuario
(no con el Service Principal: tiene `Reader` sobre la suscripcion y no
puede registrar proveedores; es el principio de minimo privilegio).

### 1. Ver credito y servicios gratuitos

**Portal**
1. Entrar a https://portal.azure.com con la cuenta de la universidad.
2. En el buscador de arriba escribir **Free services** y abrirlo.
3. Buscar **Azure Database for PostgreSQL**. Tiene que figurar el cupo
   mensual (horas de B1ms y GB de disco) y cuanto se uso.
4. Credito restante: https://www.microsoftazuresponsorships.com/Balance

Si Postgres no figura en Free services, avisar antes de crear nada: el
servidor saldria del credito (~17 USD/mes encendido).

### 2. Registrar el proveedor de PostgreSQL

Registrar un proveedor es gratis: habilita el tipo de recurso en la
suscripcion.

**Portal**
1. Buscador: **Subscriptions** (Suscripciones) > **Azure for Students**.
2. Menu izquierdo: **Settings** > **Resource providers**.
3. Filtrar por `Microsoft.DBforPostgreSQL`, seleccionarlo, **Register**.
4. Esperar 1-3 minutos hasta que el estado diga **Registered** (boton
   Refresh).

**CLI** (con tu usuario, no el Service Principal). La forma mas simple es
**Azure Cloud Shell**: el icono `>_` arriba a la derecha del Portal, elegir
Bash. Ya esta logueado con tu cuenta:

```bash
az provider register --namespace Microsoft.DBforPostgreSQL
az provider show -n Microsoft.DBforPostgreSQL --query registrationState -o tsv   # Registered
```

Error posible: `AuthorizationFailed` -> estas logueado como el Service
Principal. En Cloud Shell no pasa; en tu PC, `az login` con tu usuario.

### 3. Verificacion

Desde la terminal de este proyecto (sesion del Service Principal):

```powershell
az provider show -n Microsoft.DBforPostgreSQL --query registrationState -o tsv
az postgres flexible-server list-skus -l eastus2 --query "[0].supportedFlexibleServerEditions[?name=='Burstable'].supportedServerSkus[].name" -o tsv
```

El primero tiene que decir `Registered`; el segundo listar `Standard_B1ms`.

## Creacion de PostgreSQL (Fase 7, realizada 2026-10-11)

Resultado: servidor `psql-securepassb-um` en `centralus`, B1ms, 32 GB,
PostgreSQL 16, sin alta disponibilidad, sin backup geo-redundante, backup 7
dias, TLS obligatorio (`require_secure_transport=on`).

### Secretos

Las contrasenas se generan en la maquina local y se guardan en
`%USERPROFILE%\.securepassb\azure-secrets.env`, fuera del repo, con permisos
solo para el usuario. Nunca se escriben en el chat, en el repo ni en logs.
Contiene `PG_ADMIN_USER/PASSWORD`, `PG_APP_USER/PASSWORD`,
`SECUREPASS_JWT_SECRET` y `SECUREPASS_MASTER_KEY` (estos dos se cargan como
secretos de la Container App en la Fase 8).

Para generar una a mano en PowerShell:

```powershell
-join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | ForEach-Object {[char]$_})   # password
[Convert]::ToBase64String((1..48 | ForEach-Object { [byte](Get-Random -Max 256) }))       # JWT secret
```

### Opcion A: Azure CLI (lo que se ejecuto)

Desde Git Bash en la raiz del repo, con la sesion del Service Principal
(`Contributor` sobre `universidad` alcanza):

```bash
set -a; . ~/.securepassb/azure-secrets.env; set +a

# 1. Servidor (5-10 min). -o none: la salida incluye la password del admin.
az postgres flexible-server create -g universidad -n "$PG_SERVER" -l centralus \
  --tier Burstable --sku-name Standard_B1ms --storage-size 32 --version 16 \
  --zonal-resiliency Disabled --geo-redundant-backup Disabled --backup-retention 7 \
  --public-access 0.0.0.0 \
  --admin-user "$PG_ADMIN_USER" --admin-password "$PG_ADMIN_PASSWORD" \
  --tags proyecto=securepassb --yes -o none

# 2. Base y extension permitida
az postgres flexible-server db create -g universidad -s "$PG_SERVER" -n securepass
az postgres flexible-server parameter set -g universidad -s "$PG_SERVER" -n azure.extensions -v PGCRYPTO

# 3. Regla temporal para la IP propia (para correr el script de abajo)
MYIP=$(curl -s https://api.ipify.org)
az postgres flexible-server firewall-rule create -g universidad -s "$PG_SERVER" \
  -n tmp-setup-local --start-ip-address "$MYIP" --end-ip-address "$MYIP"

# 4. Usuario de la aplicacion, duenio de la base y de public, pgcrypto
docker run --rm -i -e PGPASSWORD="$PG_ADMIN_PASSWORD" postgres:16-alpine \
  psql "host=$PG_SERVER.postgres.database.azure.com dbname=securepass user=$PG_ADMIN_USER sslmode=require" \
  -v app_user="$PG_APP_USER" -v app_pass="$PG_APP_PASSWORD" -f - < docs/azure/setup-db.sql

# 5. Cerrar la regla temporal
az postgres flexible-server firewall-rule delete -g universidad -s "$PG_SERVER" -n tmp-setup-local --yes
```

Notas de la CLI 2.89: `--high-availability` ahora es `--zonal-resiliency`, y
`--database-name` en `create` solo vale para clusters elasticos (por eso la
base se crea aparte).

`--public-access 0.0.0.0` crea la regla `AllowAllAzureServices...`: acepta
conexiones desde cualquier IP de Azure. Hace falta porque las Container Apps
sin VNet no tienen IP de salida fija. La proteccion real es TLS obligatorio,
password de 32 caracteres y un usuario sin privilegios de administrador.
Mejora posible: VNet + acceso privado (requiere recrear el environment).

### Opcion B: Portal

1. Buscador: **Azure Database for PostgreSQL flexible servers** > **Create**.
2. **Basics**: Subscription `Azure for Students`; Resource group
   `universidad`; Server name `<NOMBRE_PG>`; Region **Central US**;
   PostgreSQL version **16**; Workload type **Development**.
3. **Configure server**: Compute tier **Burstable**, size **Standard_B1ms**,
   Storage **32 GiB**, High availability **Disabled**, Backup retention 7,
   Geo-redundancy **Disabled**. Save.
4. Authentication: **PostgreSQL authentication only**; admin username y
   password (pegarla desde el archivo de secretos, no inventarla).
5. **Networking**: Public access; marcar **Allow public access from any Azure
   service within Azure to this server**; **+ Add current client IP address**
   (temporal).
6. **Review + create** > **Create**. Esperar "Your deployment is complete".
7. En el servidor: **Settings > Databases > + Add** `securepass`.
8. **Settings > Server parameters**: buscar `azure.extensions`, marcar
   `PGCRYPTO`, **Save**.
9. Correr `docs/azure/setup-db.sql` (paso 4 de la opcion A) y despues en
   **Networking** borrar la regla de la IP propia.

### Verificacion (resultados reales)

| Prueba | Resultado |
|---|---|
| `az postgres flexible-server show` | `Ready`, Central US, Standard_B1ms, 16, 32 GB, HA y geo `Disabled` |
| Conexion como `securepass_app` | TLSv1.3, no superuser, no crea roles ni bases |
| Conexion con `sslmode=disable` | Rechazada: `no pg_hba.conf entry` |
| `max_connections` | 50, con 10 + 5 reservadas: **35 utilizables**. Pool de 10 por replica |
| Imagen del backend corriendo local contra Azure | Flyway: `Successfully applied 24 migrations ... v24`; readiness UP; login 401, protegido 401 |
| Esquema | 19 tablas, triggers `trg_access_logs_immutable` y `trg_access_log_exports_immutable` presentes |
| Firewall al cierre | Solo `AllowAllAzureServices...` (la regla temporal se borro) |

Hallazgo: en Azure el schema `public` pertenece a `azure_pg_admin`, no al
duenio de la base; sin el `ALTER SCHEMA public OWNER` del script, Flyway no
puede crear tablas.

### Operar y eliminar

```bash
az postgres flexible-server stop  -g universidad -n psql-securepassb-um   # deja de usar horas de computo (max 7 dias)
az postgres flexible-server start -g universidad -n psql-securepassb-um
az postgres flexible-server delete -g universidad -n psql-securepassb-um --yes   # BORRA los datos
```

Portal: el servidor > **Overview** > botones **Stop** / **Start** / **Delete**.

## Ahorros aplicados (Fase 7)

| Cambio | Comando | Revertir |
|---|---|---|
| ACR Standard -> **Basic** | `az acr update -n acringenieriaum --sku Basic` | `--sku Standard` |
| Log Analytics tope **0,15 GB/dia** | `az monitor log-analytics workspace update -g universidad -n workspaceuniversidadaf47 --quota 0.15` | `--quota -1` |

## Service Principal, identidades y secretos (Fase 8)

### Que es un Service Principal

Una **identidad para programas**, no para personas. Vive en Microsoft Entra
ID (el directorio del tenant) y se compone de:

| Dato | Que es | Secreto? |
|---|---|---|
| Client ID (appId) | El "usuario" del programa | No |
| Tenant ID | El directorio donde existe | No |
| Subscription ID | La suscripcion donde va a operar | No |
| Client secret | La "contrasena" del programa. Vence | **Si** |

Por si mismo no puede hacer nada: los permisos se le dan con **asignaciones
de rol** (RBAC) sobre un alcance (scope). El scope es una ruta jerarquica y
el permiso se hereda hacia abajo:

```
/subscriptions/<SUB>                                  Reader      (ver todo, no cambiar nada)
  /resourceGroups/universidad                         Contributor (crear y modificar dentro del grupo)
    /providers/Microsoft.ContainerRegistry/...        (hereda Contributor: push y pull de imagenes)
    /providers/Microsoft.App/containerApps/...        (hereda Contributor: actualizar la app)
```

Asi la suscripcion y el grupo se relacionan con el SP: el SP no "pertenece"
al grupo; el grupo es el alcance donde tiene permiso.

`Contributor` **no** puede asignar roles (`Microsoft.Authorization/
roleAssignments/write`): verificado, devuelve `AuthorizationFailed`. Es lo
correcto: si el secreto se filtra, el atacante no puede darse mas permisos
ni salir del grupo `universidad`.

Minimo privilegio ideal: `AcrPush` sobre el ACR + `Contributor` solo sobre la
Container App. Hoy tiene `Contributor` sobre todo el grupo, que incluye eso.
Reducirlo requiere que el duenio de la suscripcion cambie las asignaciones.

### Las tres identidades del sistema

| Identidad | Quien la usa | Para que | Credencial |
|---|---|---|---|
| Service Principal `542811c2...` | GitHub Actions (y la CLI local) | Login en Azure, push al ACR, actualizar la Container App | Client secret en el secret `AZURE_CREDENTIALS` de GitHub |
| Identidad administrada `id-securepassb` | La Container App | Descargar la imagen del ACR (rol `AcrPull`) | **Ninguna**: Azure emite tokens a la app; no hay contrasena que guardar ni rotar |
| Usuario `securepass_app` de Postgres | El backend en runtime | Leer y escribir su base | Password en un secreto de la Container App |

La Container App `app-azure` de la cursada descarga del ACR con el client
secret del SP guardado adentro: si se rota el secreto, deja de poder bajar
imagenes. `securepassb` usa la identidad administrada para evitar eso.

### Como lo usa GitHub Actions

```mermaid
sequenceDiagram
    participant GH as GitHub Actions
    participant AAD as Microsoft Entra ID
    participant ACR as ACR acringenieriaum
    participant ACA as Container App securepassb
    GH->>AAD: azure/login con AZURE_CREDENTIALS (clientId + secret)
    AAD-->>GH: token de acceso (rol Contributor sobre universidad)
    GH->>ACR: az acr login (usa ese token) + docker push securepassb:<tag>
    GH->>ACA: az containerapp update --image ...:<tag>
    ACA->>AAD: token de id-securepassb (rol AcrPull)
    ACA->>ACR: pull securepassb:<tag>
```

### Donde vive cada secreto

Las credenciales del **pipeline** (para hablar con Azure) y las de la
**aplicacion** (para que Spring Boot arranque) son distintas y viven en
lugares distintos. GitHub nunca ve la password de la base ni el JWT secret.

| Dato | Donde | Tipo |
|---|---|---|
| `AZURE_CREDENTIALS` (JSON del SP) | GitHub > Settings > Secrets and variables > Actions > **Secrets** | Secreto del pipeline |
| `AZURE_RESOURCE_GROUP`, `ACR_NAME`, `IMAGE_NAME`, `CONTAINER_APP_NAME`, `CONTAINER_APP_ENV`, `ACA_IDENTITY` | GitHub > ... > **Variables** | Configuracion del pipeline (no secreta) |
| `DB_PASSWORD`, `SECUREPASS_JWT_SECRET`, `SECUREPASS_MASTER_KEY` | **Secretos de la Container App**, expuestos con `secretref:` | Secretos de runtime (Fase 9) |
| `DB_URL`, `DB_USER`, `REDIS_HOST`, `SPRING_PROFILES_ACTIVE`, CORS, expiracion JWT | Variables de entorno de la Container App | Configuracion de runtime (no secreta) |
| Originales de todo lo anterior | `%USERPROFILE%\.securepassb\azure-secrets.env` | Solo en la maquina del duenio |

Por que no Key Vault: ver "Por que no Key Vault (por ahora)" arriba.

### Client secret vs OIDC

| | Client secret (lo de la cursada) | OIDC / federated credentials |
|---|---|---|
| Que guarda GitHub | El secreto del SP (vence, hay que rotarlo) | Nada secreto: solo client, tenant y subscription ID |
| Como se autentica | GitHub manda el secreto a Entra ID | GitHub emite un token firmado para *ese repo y esa rama*; Entra ID lo valida contra una "federated credential" del SP |
| Si se filtra el secreto | Cualquiera lo puede usar desde cualquier lado hasta que venza | No hay secreto que filtrar; el token dura minutos y solo vale para el repo configurado |
| Configuracion | Un secret en GitHub | Federated credential en la App registration (requiere ser duenio) + `permissions: id-token: write` en el workflow |

Se mantiene el client secret porque es el mecanismo de la catedra. Pasar a
OIDC cambia solo el paso de login del workflow:

```yaml
permissions:
  id-token: write
  contents: read
steps:
  - uses: azure/login@v2
    with:
      client-id: ${{ vars.AZURE_CLIENT_ID }}
      tenant-id: ${{ vars.AZURE_TENANT_ID }}
      subscription-id: ${{ vars.AZURE_SUBSCRIPTION_ID }}
```

y en el Portal: App registrations > el SP > Certificates & secrets >
Federated credentials > Add > GitHub Actions deploying Azure resources >
organizacion `Menguitahh`, repo `acr-securepassb`, entidad Branch `develop`.

### Pasos manuales de la Fase 8

**A. Ver cuando vence el secreto del SP.** El SP no puede leer su propia
App registration. Con tu usuario:
Portal > **Microsoft Entra ID** > **App registrations** > pestana **All
applications** > buscar por el Client ID `542811c2...` > **Certificates &
secrets** > columna **Expires**.

Recomendado: ahi mismo **+ New client secret**, descripcion
`github-acr-securepassb`, vencimiento 6 meses, **Add**, y copiar el
**Value** (se muestra una sola vez). Un secreto propio para este repo se
puede revocar sin romper `acr-um-azure`.

> **Resultado real (2026-10-11):** la cuenta de alumno recibe `401 Sin
> acceso` en Microsoft Entra ID: el tenant de la universidad no deja a los
> alumnos administrar el directorio. Por eso no se pudo ver el vencimiento
> ni crear un secreto propio, y se reutiliza el secreto de la cursada.
> Riesgo aceptado: si vence, el login del pipeline falla con
> `AADSTS7000222` (client secret expired). Solucion: pedir un secreto nuevo
> a quien administra el directorio y actualizar `AZURE_CREDENTIALS`; no se
> toca codigo. OIDC tampoco es posible por el mismo motivo.

**B. Cargar `AZURE_CREDENTIALS` en GitHub.** Es un JSON con este formato
(valores reales: Client ID, Tenant ID y Subscription ID salen del `.env` de
`acr-um-azure` o del Portal; el secreto, del paso A):

```json
{"clientId":"<CLIENT_ID>","clientSecret":"<CLIENT_SECRET>","subscriptionId":"<SUBSCRIPTION_ID>","tenantId":"<TENANT_ID>"}
```

Web: repo en GitHub > **Settings** > **Secrets and variables** > **Actions**
> pestana **Secrets** > **New repository secret** > Name
`AZURE_CREDENTIALS` > pegar el JSON en **Secret** > **Add secret**.

Terminal (PowerShell), sin que el valor aparezca en pantalla, leyendo el
`.env` de la cursada (si usas un secreto nuevo, reemplaza
`$e.AZURE_CLIENT_SECRET` por `Read-Host -AsSecureString`):

```powershell
$e = @{}; Get-Content ..\acr-um-azure\.env | Where-Object { $_ -match '^[A-Z_]+=' } | ForEach-Object { $k,$v = $_ -split '=',2; $e[$k] = $v.Trim().Trim('"') }
@{ clientId=$e.AZURE_CLIENT_ID; clientSecret=$e.AZURE_CLIENT_SECRET; subscriptionId=$e.AZURE_SUBSCRIPTION_ID; tenantId=$e.AZURE_TENANT_ID } |
  ConvertTo-Json -Compress | gh secret set AZURE_CREDENTIALS -R Menguitahh/acr-securepassb
gh secret list -R Menguitahh/acr-securepassb      # muestra solo el nombre y la fecha
```

**C. Darle `AcrPull` a la identidad `id-securepassb`.**

Portal: **Container registries** > `acringenieriaum` > **Access control
(IAM)** > **+ Add** > **Add role assignment** > rol **AcrPull** > Next >
Assign access to **Managed identity** > **+ Select members** > Managed
identity: **User-assigned managed identity** > `id-securepassb` > Select >
**Review + assign**.

CLI (Cloud Shell, con tu usuario):

```bash
az role assignment create --role AcrPull \
  --assignee-object-id $(az identity show -g universidad -n id-securepassb --query principalId -o tsv) \
  --assignee-principal-type ServicePrincipal \
  --scope $(az acr show -n acringenieriaum --query id -o tsv)
```

En Git Bash de Windows anteponer `MSYS_NO_PATHCONV=1`: si no, Git Bash
convierte `/subscriptions/...` en una ruta de Windows y Azure responde
`MissingSubscription`.

**D. Verificar.** GitHub > **Actions** > **azure-check** > **Run workflow** >
rama `develop` > **Run workflow**. Tiene que terminar en verde mostrando la
suscripcion, el grupo, el ACR y el environment.

Resultado (2026-10-11): `AZURE_CREDENTIALS` cargado, `id-securepassb` con
`AcrPull` sobre `acringenieriaum`, y `azure-check` en verde: login como
`servicePrincipal` en Azure for Students, grupo `universidad`, ACR Basic con
`az acr login` exitoso, environment `managedEnvironment-universidad-8a60`.

## Despliegue manual (Fase 9)

URL del backend: **https://securepassb.proudbush-b955ebd5.eastus.azurecontainerapps.io**

### Script `scripts/azure.py`

Reemplaza a `build.py` de la catedra. Que cambia:

| `build.py` (catedra) | `scripts/azure.py` |
|---|---|
| Despliega a **Azure Container Instances** (`az container create`) | Despliega a **Azure Container Apps** |
| Tag manual en `.env` (`IMAGE_TAG`), distinto del CI: el deploy manual podia usar una imagen que el CI nunca construyo | **Misma regla que el CI**: `vX.Y.Z` si el commit tiene tag git, si no `sha-<7>`. Ver `image_tag()` |
| Solo build, push y deploy | build, push, tags, deploy (crear o actualizar), status, revisions, logs, start, stop, delete con confirmacion |
| Defaults de otro resource group hardcodeados | Mismos nombres que las GitHub Variables, sobreescribibles por variable de entorno |

Requisitos: Docker corriendo y `az` logueado (`az account show`). La sesion
puede ser el Service Principal o tu usuario.

| Accion | Comando (desde la raiz del repo) |
|---|---|
| Que tag le toca al commit actual | `python scripts/azure.py tag` |
| Construir la imagen (amd64, etiquetas OCI) | `python scripts/azure.py build` |
| Subirla al ACR y verificar que llego | `python scripts/azure.py push` |
| Ver versiones en el ACR | `python scripts/azure.py tags` |
| Desplegar (crea la app la primera vez; despues solo cambia la imagen) | `python scripts/azure.py deploy` |
| Desplegar una version especifica (rollback) | `python scripts/azure.py deploy --tag sha-xxxxxxx` |
| Estado, URL, imagen corriendo | `python scripts/azure.py status` |
| Revisiones | `python scripts/azure.py revisions` |
| Logs (ultimas 100 / en vivo) | `python scripts/azure.py logs` / `logs -f` / `logs --container redis` |
| Detener / iniciar | `python scripts/azure.py stop` / `start` |
| Borrar la app (pide escribir el nombre) | `python scripts/azure.py delete` |

`deploy` se niega a desplegar un tag que no este en el ACR. Tests:
`cd scripts; python -m unittest test_azure.py`.

### Que crea `deploy` la primera vez

```mermaid
flowchart LR
    U[Internet / app movil] -->|HTTPS 443<br/>HTTP redirige 301| ING[Ingress de Container Apps]
    subgraph APP["Container App securepassb (0 a 1 replica)"]
        BE["backend :8080<br/>0,75 vCPU / 1,5 GiB"]
        RD["redis :6379 (127.0.0.1)<br/>0,25 vCPU / 0,5 GiB"]
        BE -->|localhost| RD
    end
    ING --> BE
    BE -->|TLS| PG[(psql-securepassb-um<br/>centralus)]
    APP -.->|id-securepassb AcrPull| ACR[(ACR)]
```

| Configuracion | Valor | Por que |
|---|---|---|
| Ingress | externo, puerto 8080, solo HTTPS | Azure pone el certificado; el backend no maneja TLS |
| Registro | `acringenieriaum.azurecr.io` con identidad `id-securepassb` | Sin contrasena guardada |
| Revisiones | modo Single | Cada deploy crea una revision nueva y apaga la anterior |
| Escala | 0 a 1 | Sin trafico no consume; Redis es por replica |
| Startup probe | `/actuator/health/liveness` cada 10 s, 30 fallos | Hasta 5 min para arrancar sin que lo maten |
| Liveness | `/actuator/health/liveness` cada 30 s | Reinicia si la JVM se cuelga |
| Readiness | `/actuator/health/readiness` cada 10 s | No le manda trafico si no hay base o Redis |
| Secretos | `db-password`, `jwt-secret`, `master-key` | Variables `DB_PASSWORD`, `SECUREPASS_JWT_SECRET`, `SECUREPASS_MASTER_KEY` con `secretRef` |
| Variables | `SPRING_PROFILES_ACTIVE=prod`, `DB_URL` (con `sslmode=require`), `DB_USER`, `REDIS_HOST=localhost`, `SECUREPASS_ACTIVATION_BASE_URL` | Configuracion no secreta (factor III) |

La spec se arma en `app_spec()` y se pasa a `az containerapp create --yaml`
desde un archivo temporal en `%USERPROFILE%\.securepassb\` que se borra
apenas termina (tiene los valores de los secretos).

### Portal: lo mismo a mano

1. **Container Apps** > **+ Create** > **Container App**. Basics: grupo
   `universidad`, nombre `securepassb`, region East US, environment
   `managedEnvironment-universidad-8a60`.
2. Container: desmarcar quickstart; Image source **Azure Container Registry**,
   registry `acringenieriaum`, image `securepassb`, tag `<TAG>`;
   autenticacion **Managed identity** `id-securepassb`; CPU 0.75 / memoria
   1.5 Gi; variables de entorno de la tabla de arriba (las secretas como
   *Reference a secret*, creando los secretos en la pestana **Secrets**).
3. Ingress: **Enabled**, **Accepting traffic from anywhere**, target port
   **8080**.
4. **Review + create**. Despues, en la app: **Containers** > **Edit and
   deploy** > **+ Add** > App container `redis`, imagen
   `docker.io/library/redis:7-alpine`, 0.25 / 0.5 Gi; **Scale**: min 0,
   max 1; **Health probes** como la tabla.

### Datos iniciales en produccion

El perfil `prod` no siembra datos (los seeds son solo `dev`). El primer barrio
se crea con el aprovisionamiento oficial del proyecto, contra la base de
Azure, abriendo y cerrando una regla de firewall temporal:

```bash
set -a; . ~/.securepassb/azure-secrets.env; set +a
MYIP=$(curl -s https://api.ipify.org)
az postgres flexible-server firewall-rule create -g universidad -s "$PG_SERVER" -n tmp-provision --start-ip-address "$MYIP" --end-ip-address "$MYIP"
SPRING_PROFILES_ACTIVE=prod \
DB_URL="jdbc:postgresql://$PG_SERVER.postgres.database.azure.com:5432/securepass?sslmode=require" \
DB_USER="$PG_APP_USER" DB_PASSWORD="$PG_APP_PASSWORD" REDIS_HOST=localhost \
SECUREPASS_ACTIVATION_BASE_URL=https://securepassb.proudbush-b955ebd5.eastus.azurecontainerapps.io \
./gradlew provisionNeighborhood -PbarrioName="<NOMBRE>" -Pslug=<slug> -Ptimezone=America/Argentina/Mendoza \
  -PgateName="Garita principal" -PadminEmail=<EMAIL> -PadminFullName="<NOMBRE>" -PadminDni=<DNI> \
  > ~/.securepassb/provision-<slug>.txt
az postgres flexible-server firewall-rule delete -g universidad -s "$PG_SERVER" -n tmp-provision --yes
```

`SECUREPASS_MASTER_KEY` y `SECUREPASS_JWT_SECRET` salen del mismo archivo:
la clave de firma de QR del barrio queda cifrada con la master key de
produccion. La salida trae el **link de activacion** (es una credencial: por
eso va a un archivo y no a la pantalla). Abrirlo en el navegador y elegir la
contrasena del admin.

Creado: barrio `Barrio Demo UM` (slug `demo-um`, garita `Garita principal`),
admin `admin@demo-um.example`, activado. Su contrasena esta en
`azure-secrets.env` como `AZ_ADMIN_PASSWORD`.

### Verificacion (2026-10-11, imagen `sha-99cb750`)

| Prueba | Resultado |
|---|---|
| Push al ACR con el Service Principal | `sha-99cb750` en el repositorio `securepassb` |
| Creacion de la app | `Running`, revision `securepassb--0jvli4k`, escala 0-1 |
| Arranque en frio (desde 0 replicas) | readiness 200 a los 28 s |
| `/actuator/health/readiness` | UP (Postgres en Azure + Redis sidecar conectados) |
| `/actuator/info` | `version: sha-99cb750`, `revision: 99cb7502...` (el commit) |
| HTTP | 301 a HTTPS; certificado valido |
| Login incorrecto / correcto | 401 / 200, JWT HS256 con rol ADMIN, `exp - iat = 900` |
| `GET /api/v1/admin/lots` sin / con token | 401 / 200 (0 lotes: barrio recien creado) |
| Token con payload adulterado | 401 |
| `/actuator/prometheus` sin token / con ADMIN | 401 / 403 |
| Refresh / reuso del refresh viejo | 200 con token nuevo / 401 |
| Firewall de Postgres al cierre | Solo `AllowAllAzureServices...` |
