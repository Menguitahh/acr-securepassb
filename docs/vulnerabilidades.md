# Analisis de vulnerabilidades de la imagen

Escaneo de la imagen Docker del backend con **Grype** (Anchore) y
contraste con **Trivy** (Aqua). Fecha: 2026-10-11.

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
| **Final** | Parches commons-lang3 y log4j-api | **70** | **2** | **20** | 24 | 6 | 18 | 0 |

Reduccion: 194 -> 70 (-64%). Criticas 10 -> 2. Altas 68 -> 20.
**Ninguna vulnerabilidad con correccion disponible queda sin aplicar**:
`grype securepassb:local --only-fixed` devuelve `No vulnerabilities found`.

Por origen, final: dependencias Java 2 criticas; paquetes Debian 20 altas,
24 medias, 6 bajas, 18 negligibles.

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

### Altas (20): paquetes de Debian 13 en la imagen base

Debian las marca `wont-fix` (no las va a parchear en trixie) o `not-fixed`
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
| Total inicial / final | 194 / 70 | 176 / 70 |
| Criticas final | 2 | 2 (las mismas) |
| Altas final | 20 | 9 |
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
