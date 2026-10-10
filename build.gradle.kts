plugins {
    java
    id("org.springframework.boot") version "3.3.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.securepass"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("me.paulschwarz:spring-dotenv:4.0.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.6.0")
    // #195: export XLSX. SXSSF en streaming con temporales cifrados.
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // ponytail: Docker Desktop en Windows expone el engine en un pipe con nombre
    // no estandar; Testcontainers no lo autodetecta. En CI (Linux) el socket
    // por defecto funciona y no se toca.
    if (System.getProperty("os.name").lowercase().contains("windows")
        && System.getenv("DOCKER_HOST") == null) {
        environment("DOCKER_HOST", "npipe:////./pipe/dockerDesktopLinuxEngine")
    }
}

// WBS-A (issue #129): entrypoint del aprovisionamiento de barrio nuevo.
// Uso:
//   ./gradlew provisionNeighborhood \
//     -PbarrioName="Barrio X" -Pslug="barrio-x" \
//     -PadminEmail="admin@x.com" -PadminFullName="Nombre" -PadminDni="30111222"
// Opcionales: -Ptimezone, -PgateName
//
// ponytail: -PbarrioName (no -Pname). Gradle ya expone project.name como
// propiedad implicita ("securepassb"), y findProperty("name") devuelve
// eso cuando falta el override: el barrio termina llamandose el proyecto.
tasks.register<JavaExec>("provisionNeighborhood") {
    group = "operations"
    description = "Aprovisiona un barrio nuevo (neighborhood + signing key + gate + admin PENDING)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.securepass.tools.NeighborhoodProvisioningCli")
    // Map: Gradle -P -> flag CLI del main.
    val cliKeys = linkedMapOf(
        "barrioName" to "name",
        "slug" to "slug",
        "timezone" to "timezone",
        "gateName" to "gate-name",
        "adminEmail" to "admin-email",
        "adminFullName" to "admin-full-name",
        "adminDni" to "admin-dni"
    )
    doFirst {
        val list = mutableListOf<String>()
        for ((gradleKey, cliKey) in cliKeys) {
            val v = project.findProperty(gradleKey)?.toString().orEmpty()
            if (v.isNotBlank()) list.add("--" + cliKey + "=" + v)
        }
        args = list
    }
    // Sin JVM args extras: hereda DB/secrets del entorno.
}
