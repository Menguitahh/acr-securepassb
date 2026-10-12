# Monitoreo y observabilidad

Como se sabe, desde Azure, que el backend funciona: no alcanza con que la
Container App diga `Running`.

## Piezas

```mermaid
flowchart LR
    subgraph APP["Container App securepassb"]
        SB["Spring Boot<br/>Actuator + Micrometer<br/>logs JSON a stdout"]
        AG["Agente OpenTelemetry de Azure Monitor<br/>(Application Insights 3.7.10, -javaagent)"]
        SB --- AG
    end
    PROBES["Probes de Container Apps<br/>liveness / readiness"] -->|HTTP| SB
    SB -->|stdout| LAW
    AG -->|requests, dependencias,<br/>excepciones, metricas| AI[Application Insights<br/>appi-securepassb]
    AI -->|guarda en| LAW[(Log Analytics<br/>workspaceuniversidadaf47)]
    ENV[Container Apps Environment] -->|eventos: arranques,<br/>probes, revisiones| LAW
```

| Senal | Fuente | Donde se ve |
|---|---|---|
| Salud | Actuator `/actuator/health/liveness` y `/readiness` (readiness incluye Postgres y Redis) | Probes de Container Apps; `curl` |
| Version corriendo | `/actuator/info` | `curl`, pipeline |
| Logs de la app | Logback JSON a stdout (perfil `prod`) | Log stream, tabla `ContainerAppConsoleLogs_CL` |
| Eventos de plataforma | Container Apps (arranques, probes fallidos, revisiones, escalado) | Tabla `ContainerAppSystemLogs_CL` |
| Requests (ruta, codigo, duracion) | Agente de Application Insights | `AppRequests`, Application Insights > Performance / Failures |
| Dependencias (cada consulta a Postgres y Redis) | Agente | `AppDependencies`, Application map |
| Excepciones | Agente | `AppExceptions`, Failures |
| Metricas (Hikari, JVM, HTTP) | Micrometer (Actuator); el agente las envia solo | `AppMetrics`; `/actuator/metrics` y `/actuator/prometheus` (solo `PLATFORM_ADMIN`) |

El agente no requiere cambios de codigo: esta en la imagen
(`/app/applicationinsights-agent.jar`, version y hash fijos en el
`Dockerfile`) y se activa con variables de la Container App:

| Variable | Valor |
|---|---|
| `JDK_JAVA_OPTIONS` | `-javaagent:/app/applicationinsights-agent.jar` |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | `secretref:appinsights-connection` |
| `APPLICATIONINSIGHTS_ROLE_NAME` | `securepassb` |

Sin esas variables (Docker Compose local) el agente no se carga.

## OpenTelemetry con Application Insights

**OpenTelemetry (OTel)** es el estandar abierto (CNCF) para generar
telemetria: **trazas** (el recorrido de una request por la app y sus
dependencias, en spans), **metricas** y **logs**, con un formato y una
propagacion de contexto comunes (W3C `traceparent`). Application Insights es
uno de los destinos posibles.

### Como esta implementado aca

El agente que corre en Azure (`applicationinsights-agent` 3.7.10, el
`-javaagent` del `Dockerfile`) **es la distribucion de OpenTelemetry de
Azure Monitor para Java**: el agente de OTel para Java (auto-instrumentacion)
mas el exportador a Application Insights. Verificado sobre el jar:

| Dato | Valor |
|---|---|
| Clases de OpenTelemetry dentro del agente | 9046 |
| Instrumentaciones OTel incluidas | 315 |
| Las que usa este backend | `spring-webmvc-6.0`, `tomcat-10.0`, `servlet-5.0`, `jdbc`, `hikaricp-3.0`, `lettuce-5.1` (Redis), `logback-appender-1.0` / `logback-mdc-1.0`, `micrometer-1.5`, `java-http-client` |

Auto-instrumentacion: el agente engancha esas librerias al arrancar la JVM;
**no hay codigo de OpenTelemetry en el backend** ni dependencias nuevas en
`build.gradle.kts`. Se activa solo en Azure (`JDK_JAVA_OPTIONS` +
`APPLICATIONINSIGHTS_CONNECTION_STRING` de la Container App).

| Senal OTel | Que genera | Tabla en Log Analytics |
|---|---|---|
| Span de servidor | Cada request HTTP (ruta, codigo, duracion) | `AppRequests` |
| Spans cliente | Cada consulta SQL a Postgres y cada comando a Redis | `AppDependencies` |
| Logs | Logs de Logback con el `trace_id` de la request en curso | `AppTraces` |
| Excepciones | Errores no manejados con stack trace | `AppExceptions` |
| Metricas | Micrometer (Hikari, JVM, HTTP) | `AppMetrics` |

Todas las piezas de una misma request comparten el **`OperationId`** (el
trace id de OTel): asi se reconstruye la traza distribuida.

### Diferencia con el proyecto de la catedra (Go)

| | Catedra (Go + Fiber) | Este backend (Java + Spring) |
|---|---|---|
| Como se instrumenta | SDK de OTel en el codigo (`pkg/telemetry`): providers, middleware, spans a mano | Agente OTel: auto-instrumentacion, sin codigo |
| Exportacion | OTLP / connection string | Exportador del agente directo a Application Insights |
| Spans de la base | Simulados (`simulate_database_query`) | Reales: cada `SELECT` contra Postgres |

En Go no existe un agente que instrumente sin tocar el codigo; en la JVM si,
y es la forma recomendada por Azure para Java.

### Traza real (2026-10-12)

```
GET /api/v1/me/passes                        134 ms   request (span raiz)
 |- postgresql  SELECT securepass             27 ms
 |- postgresql  SELECT ...signing_keys        29 ms   un span por consulta SQL
 |- postgresql  SELECT ...invitations         27 ms
 '- postgresql  SELECT ...signing_keys        27 ms
trace_id (OperationId) = 5f552de69c84ad1f35794b3e5173008b
```

De paso muestra algo util: cada consulta tarda ~27 ms porque la base esta
en `centralus` y la app en `eastus` (latencia de red, ver `docs/azure.md`).

Consulta para ver una traza completa:

```kusto
let op = toscalar(AppRequests
    | where AppRoleName == "securepassb" and Name == "GET /api/v1/me/passes"
    | top 1 by TimeGenerated desc | project OperationId);
union
  (AppRequests     | where OperationId == op | project TimeGenerated, tipo="request", Name, DurationMs),
  (AppDependencies | where OperationId == op | project TimeGenerated, tipo=DependencyType, Name, DurationMs),
  (AppTraces       | where OperationId == op | project TimeGenerated, tipo="log", Name=Message, DurationMs=real(null))
| order by TimeGenerated asc
```

### Como verlo en Azure

Los datos quedan 30 dias aunque la app este detenida; **Live metrics** y
datos nuevos necesitan la app encendida (ver "Antes de la defensa" en
`docs/guia-parcial.md`).

1. https://portal.azure.com > buscador **Application Insights** >
   **appi-securepassb**.
2. **Traza de una request (OpenTelemetry):** menu izquierdo
   **Investigate > Search** (Transaction search). Time range: ultimos 7
   dias. Click en una request, por ejemplo `GET /api/v1/me/passes`: abre
   **End-to-end transaction details** con el diagrama de Gantt (request y
   debajo cada consulta SQL con su duracion). Abajo a la derecha, **View all
   telemetry** muestra tambien los logs de esa request.
3. **Endpoints lentos:** **Investigate > Performance** > pestana
   *Operations* > elegir una operacion > **Drill into... N Samples** > una
   muestra: misma vista end-to-end.
4. **Mapa de dependencias:** **Investigate > Application map**: el nodo
   `securepassb` con flechas a `psql-securepassb-um.postgres.database.azure.com`
   y a Redis (`localhost`), con cantidad de llamadas y latencia.
5. **Errores:** **Investigate > Failures**.
6. **En vivo (app encendida):** **Investigate > Live metrics**: requests por
   segundo, duracion y fallas mientras se usa la app o Postman.
7. **Consultas:** **Monitoring > Logs** > cerrar la galeria > pegar el KQL de
   arriba > **Run**.

## Configuracion (una vez)

1. Registrar el proveedor `Microsoft.Insights` (duenio de la suscripcion):
   Portal > Subscriptions > Azure for Students > Resource providers >
   `Microsoft.Insights` > Register.
2. Crear Application Insights **basado en el workspace existente** (no crea
   otro Log Analytics; los datos cuentan para el tope de 0,15 GB/dia):
   - Portal: **Application Insights** > **Create** > grupo `universidad`,
     nombre `appi-securepassb`, region East US, Resource Mode
     **Workspace-based**, workspace `workspaceuniversidadaf47` > Create.
   - CLI (lo que se ejecuto):
     ```bash
     WS=$(az monitor log-analytics workspace show -g universidad -n workspaceuniversidadaf47 --query id -o tsv)
     az rest --method put \
       --url "https://management.azure.com/subscriptions/<SUBSCRIPTION_ID>/resourceGroups/universidad/providers/Microsoft.Insights/components/appi-securepassb?api-version=2020-02-02" \
       --body "{\"location\":\"eastus\",\"kind\":\"web\",\"properties\":{\"Application_Type\":\"web\",\"WorkspaceResourceId\":\"$WS\",\"IngestionMode\":\"LogAnalytics\"}}"
     ```
3. El **connection string** (Portal: appi-securepassb > Overview >
   Connection String) va a `%USERPROFILE%\.securepassb\azure-secrets.env`
   como `APPINSIGHTS_CONNECTION_STRING`. Identifica el recurso donde se
   envia telemetria: no se commitea.
4. Activarlo en la app (crea una revision nueva):
   `python scripts/azure.py monitor`

Costo: la ingesta va al workspace (5 GB/mes gratis, tope de 0,15 GB/dia).

## Preguntas de la defensa

Las consultas se corren en el Portal: **Log Analytics workspaces** >
`workspaceuniversidadaf47` > **Logs** (o Application Insights > Logs), modo
KQL, pegar y **Run**.

**Como sabemos si Spring Boot esta funcionando?** Readiness (que ademas
prueba Postgres y Redis) y requests atendidas:

```bash
curl https://securepassb.proudbush-b955ebd5.eastus.azurecontainerapps.io/actuator/health/readiness
```

**Como sabemos si se conecta a la base?** Readiness `UP`, y cada consulta
queda registrada como dependencia:

```kusto
AppDependencies
| where TimeGenerated > ago(1h) and AppRoleName == "securepassb"
| summarize n=count(), p95=percentile(DurationMs, 95), ok=countif(Success) by DependencyType, Target
```

Resultado: `postgresql | psql-securepassb-um.postgres.database.azure.com | 13 | 25,6 ms | 13 ok`
y `redis | localhost | 12 | 1,4 ms | 12 ok`.

**Como sabemos si una peticion llego al servidor?**

```kusto
AppRequests
| where TimeGenerated > ago(1h) and AppRoleName == "securepassb"
| summarize n=count() by Name, ResultCode
| order by n desc
```

Resultado: `GET /api/v1/admin/lots 200 x10`, `401 x2`, `POST /api/v1/auth/login 200 x1 / 401 x2`, `GET /** 404 x1`, ...

**Como sabemos si el login funciono?**

```kusto
AppRequests
| where TimeGenerated > ago(1h) and Name == "POST /api/v1/auth/login"
| summarize intentos=count() by ResultCode, Success
```

Resultado: `401 False 2`, `200 True 1` (dos intentos con password incorrecta, uno correcto).

**Como medimos cuanto tardo una peticion?** Tiempo del lado del servidor
(sin la red del cliente):

```kusto
AppRequests
| where TimeGenerated > ago(1h) and AppRoleName == "securepassb"
| summarize n=count(), p50=percentile(DurationMs, 50), p95=percentile(DurationMs, 95), max=max(DurationMs) by Name
```

Resultado: login p50 476 ms (BCrypt con costo 12 en 0,75 vCPU, es a
proposito: frena fuerza bruta); `GET /api/v1/admin/lots` p50 33 ms;
health 56 ms. Portal: Application Insights > **Performance**.

**Como vemos un error?**

```kusto
union
  (AppExceptions | where TimeGenerated > ago(1h) | project TimeGenerated, tipo="excepcion", detalle=ProblemId),
  (AppRequests | where TimeGenerated > ago(1h) and toint(ResultCode) >= 500 | project TimeGenerated, tipo="5xx", detalle=Name)
```

En la prueba: sin filas (no hubo errores del servidor). Logs del backend
por nivel:

```kusto
ContainerAppConsoleLogs_CL
| where ContainerAppName_s == "securepassb" and ContainerName_s == "backend"
| extend nivel = extract('"level":"([A-Z]+)"', 1, Log_s)
| summarize n=count() by nivel
```

Portal: Application Insights > **Failures** (agrupa por excepcion y
endpoint, con el stack trace).

**Como revisamos los logs desde Azure?** Container App > **Monitoring** >
**Log stream** (en vivo), o **Logs** con KQL, o
`python scripts/azure.py logs -f`.

**Como sabemos si la aplicacion se esta reiniciando?**

```kusto
ContainerAppSystemLogs_CL
| where ContainerAppName_s == "securepassb"
| summarize n=count() by Reason_s
| order by n desc
```

Muchos `ContainerStarted` / `ProbeFailed` en poco tiempo = reinicios en
bucle. En la prueba, los `ProbeFailed` coinciden con cada arranque (la
startup probe falla hasta que Spring termina de levantar) y los
`ContainerStarted` con deploys, stop/start y escalado desde cero. Tambien:
Container App > **Metrics** > *Restart Count*.

**Como identificamos si fallaron variables de entorno?** La app no arranca y
el motivo queda en el log (mensajes verificados en `docs/arquitectura.md`):

```kusto
ContainerAppConsoleLogs_CL
| where ContainerAppName_s == "securepassb"
| where Log_s has_any ("Could not resolve placeholder", "URL must start with 'jdbc'",
                       "password authentication failed", "debe tener al menos", "Unable to connect to Redis")
| project TimeGenerated, Log_s
```

**Metricas de Hikari en Azure:**

```kusto
AppMetrics
| where TimeGenerated > ago(1h) and AppRoleName == "securepassb" and Name startswith "hikaricp_"
| summarize arg_max(TimeGenerated, Sum, ItemCount) by Name
| project Name, valor = Sum / ItemCount
```

Resultado: `max 10`, `min 2`, `idle 3`, `active 0`, `pending 0`,
`timeout 0` (nadie espero conexion).

## Vistas del Portal para mostrar

| Pregunta | Donde |
|---|---|
| Que pasa ahora mismo | Application Insights > **Live metrics** (requests/s, latencia, fallas en vivo) |
| Que endpoints son lentos | Application Insights > **Performance** |
| Que falla | Application Insights > **Failures** |
| Con que habla el backend | Application Insights > **Application map** (backend -> Postgres, Redis) |
| CPU, memoria, replicas, reinicios | Container App > **Metrics** |
| Eventos de la plataforma | Container App > **Monitoring** > **Logs** (`ContainerAppSystemLogs_CL`) |

## 12 factores

| Factor | Como se cumple |
|---|---|
| **III. Config** | Toda la configuracion por variables de entorno: `application.yml` lee `${DB_URL}`, `${SECUREPASS_JWT_SECRET}`...; en Azure son variables y secretos de la Container App. La misma imagen corre en local y en Azure |
| **V. Build, release, run** | Build: el CI genera una imagen inmutable `sha-<7>`/`vX.Y.Z`. Release: imagen + configuracion de la Container App = una revision. Run: Container Apps ejecuta la revision. Rollback = activar otra combinacion, sin rebuild |
| **VII. Port binding** | La app trae su servidor (Tomcat embebido) y escucha en 8080; no depende de un servidor externo. Azure solo apunta su ingress a ese puerto |
| **XI. Logs** | La app escribe eventos a stdout como stream (JSON en prod) y no maneja archivos ni rotacion; la plataforma los captura y los manda a Log Analytics |

El factor XI es solo una parte de la observabilidad: cubre los **logs**. Las
**metricas** (Hikari, latencias), las **trazas** (request -> consulta SQL) y
los **health checks** son senales distintas, cada una con su herramienta.

## Verificacion (2026-10-12)

| Prueba | Resultado |
|---|---|
| Agente en Azure | `Application Insights Java Agent 3.7.10 started successfully`; Spring arranco en 9,6 s |
| Trafico generado | 2 logins fallidos, 1 correcto, 10 lecturas con token, 3 sin token, 2 accesos a metricas con ADMIN (403), 1 ruta inexistente |
| `AppRequests` | 25 requests en el workspace a los pocos minutos, con codigo y duracion |
| `AppDependencies` | Postgres 13 llamadas p95 25,6 ms; Redis 12 llamadas p95 1,4 ms; todas OK |
| `AppMetrics` | Metricas de Hikari, JVM, executor, tiempos de arranque |
| Grype con el agente | Sin cambios: 70 vulnerabilidades, ninguna con fix pendiente |
