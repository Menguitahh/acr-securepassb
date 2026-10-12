# syntax=docker/dockerfile:1
#
# Imagen del backend. Estructura de la plantilla de la catedra
# (base -> builder -> runtime sin shell), adaptada de Maven a Gradle.
#
# Construir (desde la raiz del repo):
#   docker build --platform linux/amd64 -t securepassb:dev .
# Los tests no corren aca: los corre el CI antes del build (Testcontainers
# necesita Docker y no hay Docker dentro de un docker build).

# --- base: solo descarga dependencias (equivale a pom.xml + mvn dependency:resolve).
# Esta capa se reutiliza mientras no cambie build.gradle.kts.
# Sin apt-get build-essential: Java no compila codigo nativo.
FROM eclipse-temurin:21-jdk-noble AS base
WORKDIR /java/app/base

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew resolveDependencies --no-daemon -q

# --- builder: compila el jar ejecutable (equivale a mvn clean package -DskipTests).
FROM eclipse-temurin:21-jdk-noble AS builder
WORKDIR /java/app/builder

# Cache de Gradle de la etapa base (en Maven: /root/.m2).
COPY --from=base /root/.gradle /root/.gradle
COPY . .
# HealthCheck.java: el HEALTHCHECK no puede usar curl (la base no tiene shell ni curl).
RUN ./gradlew clean bootJar -x test --no-daemon -q \
 && javac -d /healthcheck docker/HealthCheck.java

# Agente de Application Insights (Azure Monitor). Version y hash fijos: si el
# archivo de Maven Central cambia, el build falla. ADD desde una URL deja el
# archivo con permisos 600 (solo root): en runtime se copia con --chmod=444.
ARG APPINSIGHTS_AGENT_VERSION=3.7.10
ADD --checksum=sha256:93a70c8f5d364c7e777f6c4d1b235dba91aef8448bd3fa94359f1d7f3e2eb0ec \
    https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/${APPINSIGHTS_AGENT_VERSION}/applicationinsights-agent-${APPINSIGHTS_AGENT_VERSION}.jar \
    /agent/applicationinsights-agent.jar


# --- jre: solo aporta el JRE 21 de Temurin (se copia el directorio).
FROM eclipse-temurin:21-jre-noble AS jre

# --- runtime: Chainguard glibc-dynamic, sin shell ni gestor de paquetes.
# Reemplaza a distroless java21-debian13: esa base trae 68 vulnerabilidades
# de paquetes Debian sin parche (libc, expat, zlib, libuuid, libstdc++);
# glibc-dynamic trae solo glibc, certificados y usuarios (Grype: 0). El JRE
# no viene en la base: se copia el de Temurin, asi Java queda en 21 LTS.
# Digest fijo (build reproducible); Dependabot abre un PR cuando Chainguard
# publica una base nueva con parches (.github/dependabot.yml).
FROM cgr.dev/chainguard/glibc-dynamic:latest@sha256:2a3f1ec8a825dfbb1211b5835a9b8b3fb8d76c7bd22a189b1f84157ed2028293 AS runtime
COPY --from=jre /opt/java/openjdk /opt/java/openjdk

ARG VERSION=dev
ARG REVISION=unknown
ARG CREATED=unknown
ARG SOURCE=https://github.com/Menguitahh/acr-securepassb

LABEL org.opencontainers.image.title="securepassb" \
      org.opencontainers.image.description="Backend de Securepass: control de acceso para barrios privados" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.source="${SOURCE}"

# Opciones de la JVM por JAVA_TOOL_OPTIONS (la JVM la lee sola). Un
# ${JAVA_OPTS} dentro del ENTRYPOINT en formato JSON no se expande: no hay
# shell. Para cambiarlas: -e JAVA_TOOL_OPTIONS="..." al correr el contenedor.
# APP_VERSION / APP_REVISION se ven en /actuator/info; el CI las verifica al
# desplegar.
ENV JAVA_HOME=/opt/java/openjdk \
    PATH=/opt/java/openjdk/bin:/usr/bin:/bin \
    SPRING_OUTPUT_ANSI_ENABLED=ALWAYS \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    APP_VERSION=${VERSION} \
    APP_REVISION=${REVISION}

USER nobody
WORKDIR /app

COPY --from=builder /java/app/builder/build/libs/app.jar /app/app.jar
COPY --from=builder /healthcheck/HealthCheck.class /healthcheck/
# Inactivo salvo que el entorno defina
# JDK_JAVA_OPTIONS=-javaagent:/app/applicationinsights-agent.jar (lo hace la
# Container App, junto con APPLICATIONINSIGHTS_CONNECTION_STRING).
COPY --from=builder --chmod=444 /agent/applicationinsights-agent.jar /app/applicationinsights-agent.jar
EXPOSE 8080

# Timeout amplio: si JDK_JAVA_OPTIONS activa el agente de Application Insights,
# la JVM del chequeo tambien lo carga (~5 s). Azure Container Apps no usa este
# HEALTHCHECK (usa sus probes HTTP); aplica a Docker y Compose.
HEALTHCHECK --interval=30s --timeout=20s --start-period=60s --retries=3 \
    CMD ["java", "-cp", "/healthcheck", "HealthCheck"]

ENTRYPOINT [ "java", "-Djava.security.egd=file:/dev/./urandom", "-jar", "/app/app.jar" ]
