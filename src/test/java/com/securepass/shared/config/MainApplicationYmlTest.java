package com.securepass.shared.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El application.yml de test reemplaza al de main en el classpath, y los
 * tests de integracion reciben base y Redis de Testcontainers: un bloque mal
 * indentado en main (spring.data.redis colgando de otra clave) no lo detecta
 * ningun otro test y en el contenedor el backend termina en localhost.
 */
class MainApplicationYmlTest {

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/" + file));
        return yaml.getObject();
    }

    @Test
    void conexionesSeLeenDeVariablesDeEntorno() {
        Properties p = load("application.yml");
        assertThat(p.getProperty("spring.datasource.url")).startsWith("${DB_URL");
        assertThat(p.getProperty("spring.data.redis.host")).startsWith("${REDIS_HOST");
        assertThat(p.getProperty("spring.data.redis.port")).startsWith("${REDIS_PORT");
        assertThat(p.getProperty("securepass.jwt.secret")).isEqualTo("${SECUREPASS_JWT_SECRET}");
    }

    @Test
    void openApiQuedaEn30() {
        assertThat(load("application.yml").getProperty("springdoc.api-docs.version")).isEqualTo("openapi_3_0");
    }
}
