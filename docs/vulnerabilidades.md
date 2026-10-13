# Analisis de vulnerabilidades de la imagen

Escaneo de la imagen Docker del backend con **Grype** (Anchore) y
contraste con **Trivy** (Aqua). Fechas: 2026-10-11 y 2026-10-12.

## Conceptos

- **CVE** (Common Vulnerabilities and Exposures): identificador unico de una
  vulnerabilidad publica, por ejemplo `CVE-2026-41293`. Los avisos de GitHub
  usan ademas un `GHSA-...` que apunta al mismo CVE.
- **CVSS** (Common Vulnerability Scoring System): puntaje de 0 a 10 de la
  gravedad *tecnica* (vector de ataque, complejidad, privilegios, impacto).
  0.1-3.9 baja, 4.0-6.9 media, 7.0-8.9 alta, 9.0-10 critica. No mide si la
  aplicacion usa el codigo vulnerable: eso es el analisis de riesgo de abajo.
- **Estado del fix** (Grype): `fixed` hay version corregida; `not-fixed` el
  proveedor todavia no la publico; `wont-fix` el proveedor (Debian) decidio
  no corregirla en esa version de la distribucion.

## Como correrlo

Sin instalar nada, con las imagenes oficiales:

```powershell
docker build -t securepassb:local .
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -v grype-db:/.cache/grype `
  anchore/grype:latest securepassb:local
# solo las que tienen fix publicado; sale con error si alguna es critica (gate del CI):
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -v grype-db:/.cache/grype `
  anchore/grype:latest securepassb:local --only-fixed --fail-on critical
# Trivy
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock -v trivy-cache:/root/.cache/ `
  aquasec/trivy:latest image securepassb:local
```

Herramientas usadas: Grype 0.120.1, Trivy 0.75.0.

## Resultados

| Etapa | Cambio | Total | Criticas | Altas | Medias | Bajas | Negligibles | Sin clasificar |
|---|---|---|---|---|---|---|---|---|
| Inicial | Spring Boot 3.3.13 + distroless debian12 | **194** | **10** | **68** | 74 | 21 | 18 | 3 |
| Paso 1 | Base `java21-debian13` (JRE 21.0.10 -> 21.0.12.1) | 150 | 9 | 53 | 53 | 17 | 18 | 0 |
| Paso 2 | Spring Boot 3.3.13 -> 3.5.16 | 90 | 6 | 27 | 33 | 6 | 18 | 0 |
| Paso 3 | Parches Tomcat, Netty, Jackson, driver Postgres | 72 | 2 | 20 | 26 | 6 | 18 | 0 |
| Paso 4 | Parches commons-lang3 y log4j-api | 70 | 2 | 20 | 24 | 6 | 18 | 0 |
| **Final** | Base Chainguard `glibc-dynamic` + JRE 21 de Temurin | **2** | **2** | **0** | 0 | 0 | 0 | 0 |

Reduccion: 194 -> 2 (-99%). Criticas 10 -> 2. Altas 68 -> 0. Sistema
operativo de la imagen: 0 (Grype y Trivy coinciden).
**Ninguna vulnerabilidad con correccion disponible queda sin aplicar**:
`grype securepassb:local --only-fixed` devuelve `No vulnerabilities found`.

Por origen, final: 2 criticas en `spring-webmvc` (dependencia Java);
sistema operativo 0.

### Correcciones aplicadas

| Cambio | Archivo | Que corrige (ejemplos) |
|---|---|---|
| Base `gcr.io/distroless/java21-debian13:nonroot` | `Dockerfile` | 1 critica de `libc6` (CVE-2026-5450, CVSS 9.8); 20 vulnerabilidades del JRE 21.0.10 (4 altas) (CVE-2026-34282, CVE-2026-22016, ...); `libexpat1`, `libpng`, `liblcms2` desactualizados |
| Spring Boot 3.3.13 -> 3.5.16 | `build.gradle.kts` | La rama 3.3 ya no recibe parches. Tomcat 10.1.42 -> 10.1.55 (CVE-2026-41293, CVE-2026-43512, 9.8), Spring Security (CVE-2026-22732, 9.1), Jackson, logback (CVE-2025-11226), commons-lang3 |
| springdoc 2.6.0 -> 2.8.17 | `build.gradle.kts` | Version compatible con Boot 3.5 |
| `tomcat.version` 10.1.60 | `build.gradle.kts` | CVE-2026-65905 (9.8), CVE-2026-65182 (9.1), CVE-2026-68525 (9.1) |
| `netty.version` 4.1.137.Final | `build.gradle.kts` | CVE-2026-75595 (9.1), CVE-2026-59901 (8.7) |
| `jackson-bom.version` 2.21.7 | `build.gradle.kts` | CVE-2026-89407, CVE-2026-91777 y otras 3 altas |
| `postgresql.version` 42.7.12 | `build.gradle.kts` | CVE-2026-54291 (8.2) |
| `commons-lang3.version` 3.18.0, `log4j2.version` 2.25.5 | `build.gradle.kts` | CVE-2025-48924, CVE-2026-49844 (medias) |
| Runtime `cgr.dev/chainguard/glibc-dynamic` + `/opt/java/openjdk` de `eclipse-temurin:21-jre-noble` | `Dockerfile` | Las 68 de Debian 13 (libc, expat, zlib, libuuid, libstdc++, gcc): esa base trae solo glibc, certificados y usuarios |

Efectos colaterales del upgrade, resueltos en el mismo cambio:

- springdoc 2.8 emite OpenAPI 3.1 por defecto, donde `nullable` no existe:
  12 campos de respuesta perdian `X | null` en el cliente del front. Se fijo
  `springdoc.api-docs.version: openapi_3_0`. El contrato regenerado
  (`openapi.json`) solo suma restricciones que el backend ya validaba
  (`minLength`, `format: email`, `minimum/maximum`) y renumera 10
  `operationId` que el front no usa (usa paths).
- `@MockBean`/`@SpyBean` quedaron deprecados: migrados a
  `@MockitoBean`/`@MockitoSpyBean` en 10 tests.
- Suite completa: 770/770, 0 warnings de compilador. Contenedor probado:
  healthy, login 200, endpoints 401/200.

## Vulnerabilidades pendientes y justificacion

### Criticas (2): Spring Framework, sin fix en la rama 6.2

| CVE | CVSS | Componente | Que afecta | Riesgo en este backend |
|---|---|---|---|---|
| CVE-2026-47884 | 9.8 | spring-webmvc 6.2.19 | `XsltView` (vistas XSLT) | **No aplicable**: ninguna clase usa `XsltView` ni XSLT (verificado con grep sobre `src/main`) |
| CVE-2026-47890 | 9.8 | spring-webmvc 6.2.19 | Server-Sent Events al renderizar fragmentos | **No aplicable**: no hay `SseEmitter`, `ServerSentEvent` ni `FragmentsRendering` en el codigo |

Trivy informa que se corrigen en Spring Framework 7.0.9, que llega con
Spring Boot 4 (Jakarta EE 11, Jackson 3): una migracion mayor que no se hace
para cerrar dos CVE sobre funciones que no se usan. Accion: revisar en cada
escaneo; si el codigo empieza a usar XSLT o SSE, migrar antes.

### Historico: las 68 de Debian 13 (resueltas cambiando la base)

Con la base `distroless/java21-debian13` quedaban 20 altas, 24 medias, 6
bajas y 18 negligibles. Debian las marca `wont-fix` (no las va a parchear en trixie) o `not-fixed`
(todavia sin parche). No hay version corregida que instalar: se actualizan
solas cuando distroless publique la base con el parche (reconstruir la
imagen las toma).

| Paquete | CVE | Por que el riesgo es bajo aca |
|---|---|---|
| `libexpat1` | CVE-2026-66046, -77214, -93990, -76956, -76957 | Parser XML en C. La JVM parsea XML con su propio parser Java; el backend no le pasa XML a expat |
| `libc6`, `libc-bin` | CVE-2026-5435, CVE-2026-19499 | Funciones deprecadas (`ns_printrr`, `fp_nquery`) y `strfmon` con relleno: la JVM no las llama |
| `zlib1g` | CVE-2026-85091 | Overflow en `gzwrite` no bloqueante. `java.util.zip` usa la API deflate, no `gzwrite` |
| `libstdc++6`, `libgcc-s1`, `gcc-14-base` | CVE-2026-95619, CVE-2026-102010 | Requieren tamanos controlados por el atacante en codigo C++ (`aligned new`, `erase_if`); las entradas HTTP no llegan a ese codigo |
| `libuuid1` | CVE-2026-76642, -78408, -78410, -78409 | Fallas de `mount` y `nsenter` (util-linux). Esos binarios no estan en la imagen: solo la biblioteca `libuuid` |

Mitigaciones que aplican a todas: imagen distroless sin shell ni gestor de
paquetes, proceso con usuario no-root (UID 65532), sin binarios de sistema
que un atacante pueda invocar.

## Grype vs Trivy

Misma imagen final, mismo dia:

| | Grype 0.120.1 | Trivy 0.75.0 |
|---|---|---|
| Total inicial / final (base debian13) | 194 / 70 | 176 / 70 |
| Total final (base glibc-dynamic) | 2 | 2 |
| Criticas final | 2 | 2 (las mismas) |
| Altas con base debian13 | 20 | 9 |
| Severidad de paquetes Debian | CVSS de NVD | Rating propio de Debian (suele ser menor) |
| Fix de las 2 criticas de Spring | "not-fixed" (mira la rama 6.2) | "fixed in 7.0.9" (informa la rama siguiente) |

Diferencias de fondo:

- **Fuentes**: Grype usa la base de Anchore (NVD + GitHub Advisories +
  feeds de distros); Trivy la de Aqua (similar, con prioridad al rating del
  vendor). Por eso una misma CVE de Debian puede salir alta en Grype y media
  en Trivy.
- **Alcance**: Grype solo busca vulnerabilidades (el inventario lo arma
  Syft). Trivy ademas detecta secretos, malas configuraciones
  (Dockerfile, Kubernetes, Terraform) y licencias.
- **Uso en el pipeline**: los dos fallan el build con un umbral
  (`--fail-on critical` en Grype, `--exit-code 1 --severity CRITICAL` en
  Trivy) y exportan SARIF para GitHub.

Se usa Grype como herramienta principal (la de la catedra) y Trivy como
segunda opinion.

## Paso final: cambio de imagen base (2026-10-12)

Las 68 vulnerabilidades restantes de la imagen eran de paquetes de Debian 13
sin parche. Se midieron alternativas de runtime para Java 21 con Grype:

| Base | Total | Altas | Java | Nota |
|---|---|---|---|---|
| `gcr.io/distroless/java21-debian13:nonroot` | 68 | 20 | 21 | La que se usaba; ninguna con fix |
| `eclipse-temurin:21-jre-alpine` | 67 | 13 | 21 | Ademas trae shell y apk |
| `bellsoft/liberica-runtime-container:jre-21-slim-musl` | 10 | 10 | 21 | |
| `ubuntu/jre:21-24.04_stable` (chiseled) | 9 | 7 (+1 critica) | 21 | Las 9 son del binario Go `pebble`, no de Java |
| `cgr.dev/chainguard/jre:latest` | 3 | 0 | **27** | El tag gratis solo tiene la ultima version de Java |
| **`cgr.dev/chainguard/glibc-dynamic` + JRE de Temurin 21** | **0** | **0** | **21** | Elegida |

Como queda el `Dockerfile`: una etapa `jre` (`eclipse-temurin:21-jre-noble`)
de la que solo se copia `/opt/java/openjdk`, y el runtime sobre
`glibc-dynamic` con el digest fijo. `.github/dependabot.yml` abre un PR
cuando hay una version nueva de la base (y de dependencias y acciones); el
CI con Grype valida cada uno.

Que se verifico con la base nueva:

| Prueba | Resultado |
|---|---|
| Librerias que pide el JRE (`ldd`) | Solo glibc (la base la trae); X11/ALSA son de AWT grafico, que un servidor headless no carga; zlib viene en el JRE |
| Usuario | `nobody` existe (UID 65534); el proceso corre como `nobody`, sin shell |
| Arranque, login, endpoints | healthy, login 200, protegido 401/200 |
| Export CSV y XLSX (Apache POI) | 200; el `.xlsx` abre con 77 filas (el codigo evita `autoSizeColumn`, que necesita fuentes) |
| TLS saliente con el JRE copiado | El agente de Application Insights envio telemetria a Azure por HTTPS sin errores de certificados |
| Grype / Trivy | 2 / 2 (las de Spring); sistema operativo 0 |
| Syft (inventario) | 7 paquetes de la base, el binario `openjdk` y 122 jars: Grype ve todo el contenido |

Hallazgo de paso: con el agente activado, la JVM del `HEALTHCHECK` tambien
lo cargaba y superaba el timeout de 5 s (unhealthy con la app sana). Se
paso a 20 s. Azure Container Apps no usa el `HEALTHCHECK` de Docker.

### Las 2 que quedan

CVE-2026-47884 y CVE-2026-47890 en `spring-webmvc` 6.2.19 (ver arriba: XSLT
y SSE con fragmentos, no usados). 6.2.19 es la ultima version de la rama
6.2 en Maven Central; la correccion esta en Spring Framework 7.0.9, que
llega con Spring Boot 4. Llegar a 0 exige migrar a Boot 4 (Jackson 3,
Spring Security 7, Hibernate 7, springdoc 3): un cambio grande con riesgo de
regresiones en la logica de negocio, para cerrar dos CVE sobre funciones que
el backend no usa. Se deja documentado y no se oculta en los informes.

## Snyk vs Grype vs Trivy (2026-10-13)

Snyk web (app.snyk.io, importando el repo de GitHub) mostraba **0**, Grype y
Trivy **2**. No se contradicen: miran cosas distintas con bases distintas.

| Herramienta | Que escanea | Base de datos | Resultado (antes de los parches de abajo) |
|---|---|---|---|
| **Snyk web** (repo importado) | Lee los archivos del repo. Del `Dockerfile` toma la **imagen base** (`glibc-dynamic`: 7 paquetes). `build.gradle.kts` no tiene las versiones (las pone el plugin de Spring Boot al correr Gradle) y sin `gradle.lockfile` no puede resolverlas | Snyk | **0** |
| **Snyk CLI** `snyk test` | Ejecuta Gradle y resuelve el arbol real (177 dependencias) | Snyk | **25** (1 critica, 15 altas, 8 medias, 1 baja) |
| **Snyk CLI** `snyk container test --app-vulns` | La imagen construida: base (0) + los jars de `/app` (120) | Snyk | **25** (las mismas) |
| **Grype** / **Trivy** | La imagen construida | GitHub Advisories + NVD (Grype), Aqua (Trivy) | **2** |

Las 2 de Grype estan entre las 25 de Snyk. La diferencia de cantidad es de
base de datos: Snyk tiene avisos propios que la base de GitHub (la que usa
Grype, actualizada al 2026-10-12) todavia no incluia. Leccion: el
resultado depende de **que** se escanea (repo vs imagen) y **contra que
base**; por eso conviene mas de una herramienta.

Comandos (requieren `npx snyk auth` una vez):

```powershell
npx snyk test                                                         # proyecto Gradle
npx snyk container test securepassb:local --file=Dockerfile --app-vulns  # imagen completa
```

### Correcciones a partir de Snyk

| Cambio (`build.gradle.kts`) | Corrige |
|---|---|
| `postgresql.version` 42.7.12 -> **42.7.14** | CVE-2026-107314 (alta), CVE-2026-107315 (media) |
| `logback.version` -> **1.6.5** | CVE-2026-13006 (alta), CVE-2026-19880, CVE-2026-104721 (medias) |
| `micrometer.version` -> **1.16.7** | CVE-2026-59295, CVE-2026-59296 (altas) |
| `reactor-bom.version` -> **2025.0.7** (reactor-core 3.8.7, via Lettuce/Redis) | CVE-2026-47857, CVE-2026-47863 (altas) |

Logback, Micrometer y Reactor son una version menor arriba de la que trae
Boot 3.5. Verificado: 780 tests en verde, 0 warnings; contenedor en perfil
`prod` con logs JSON (logback 1.6 + logstash encoder) sin errores, refresh
de sesion por Redis 200, `/actuator/prometheus` 200 con metricas de Hikari,
export XLSX 200, agente de Application Insights sin errores.

Resultado: **Snyk 25 -> 16; Grype 2; Trivy 2.** Las 16 de Snyk son todas de
la familia Spring (`spring-web`, `spring-webmvc`, `spring-beans`,
`spring-expression`, `spring-context-support`, `spring-security-core`,
`spring-security-crypto`, `spring-data-jpa`) y solo se corrigen en Spring
Framework 7 / Spring Security 7 / Spring Data 4, es decir migrando a Spring
Boot 4 (ver arriba por que no se hizo).
