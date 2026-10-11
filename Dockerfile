# syntax=docker/dockerfile:1
#
# Imagen del backend. Dos etapas:
#   build:   JDK 21 + Gradle wrapper -> jar ejecutable, extraido en capas.
#   runtime: distroless Java 21 (sin shell ni gestor de paquetes), usuario no-root.
#
# Construir (desde la raiz del repo):
#   docker build --platform linux/amd64 -t securepassb:dev .
# Los tests no corren aca: los corre el CI antes del build (Testcontainers
# necesita Docker y no hay Docker dentro de un docker build).

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# 1) Solo los archivos de build: esta capa (dependencias) se reutiliza
#    mientras no cambie build.gradle.kts.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon -q > /dev/null

# 2) Codigo y empaquetado.
COPY src src
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon -q -x test \
 && java -Djarmode=tools -jar "$(ls build/libs/*.jar | grep -v -- -plain)" \
        extract --layers --launcher --destination /extracted

COPY docker/HealthCheck.java /healthcheck/
RUN javac -d /healthcheck /healthcheck/HealthCheck.java


FROM gcr.io/distroless/java21-debian12:nonroot AS runtime

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

WORKDIR /app

# De lo que menos cambia a lo que mas cambia: un cambio de codigo solo
# reconstruye la ultima capa.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
COPY --from=build /healthcheck/HealthCheck.class /healthcheck/

# Version visible en /actuator/info. Sin secretos: llegan en runtime.
ENV APP_VERSION=${VERSION} \
    APP_REVISION=${REVISION} \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

USER 65532:65532
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
    CMD ["java", "-cp", "/healthcheck", "HealthCheck"]

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
