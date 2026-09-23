package com.securepass;

import io.swagger.v3.oas.annotations.media.Schema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// springdoc nombra cada schema por el simple name de la clase; dos DTOs con el
// mismo nombre colapsan en un solo schema y uno pisa al otro en silencio.
class OpenApiSchemaNamesTest {

    @Test
    void dtosRestNoCompartenNombreDeSchema() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getClassMetadata().getClassName().contains(".rest."));

        Map<String, List<String>> byName = scanner.findCandidateComponents("com.securepass").stream()
                .map(BeanDefinition::getBeanClassName)
                .map(OpenApiSchemaNamesTest::load)
                .filter(Class::isRecord)
                .collect(Collectors.groupingBy(OpenApiSchemaNamesTest::schemaName,
                        Collectors.mapping(Class::getName, Collectors.toList())));

        assertThat(byName.values()).allSatisfy(classes -> assertThat(classes).hasSize(1));
    }

    private static String schemaName(Class<?> type) {
        Schema schema = type.getAnnotation(Schema.class);
        if (schema == null || schema.name().isBlank()) {
            return type.getSimpleName();
        }
        return schema.name();
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
