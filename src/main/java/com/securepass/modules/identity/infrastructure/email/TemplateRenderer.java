package com.securepass.modules.identity.infrastructure.email;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lector simple de plantillas en {@code classpath:email-templates/}. Cachea
 * el contenido crudo y reemplaza {@code ${var}} por su valor. No pretende
 * ser un motor de plantillas: escapado de HTML/URL es responsabilidad del
 * caller (aca no metemos ningun valor de usuario en HTML sin controlar la
 * ruta primero).
 */
@Component
public class TemplateRenderer {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String render(String templateName, Map<String, String> vars) {
        String template = cache.computeIfAbsent(templateName, this::load);
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out = out.replace("${" + e.getKey() + "}", value);
        }
        return out;
    }

    private String load(String name) {
        try {
            return new String(new ClassPathResource("email-templates/" + name).getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("plantilla no encontrada: " + name, e);
        }
    }
}
