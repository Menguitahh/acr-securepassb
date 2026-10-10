package com.securepass.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class OpenApiConfig {

    /**
     * #196: respuestas de /api/v1/admin/**. springdoc solo marca required lo
     * anotado y ningun DTO del admin lo estaba: el front tenia que poner un
     * default por campo y un dato faltante se veia como un 0. Aca todos los
     * campos van required; los que pueden no tener valor van ademas nullable
     * (la clave viene siempre, con null). Un DTO de admin nuevo se agrega aca:
     * AdminResponsesRequiredTest falla si una respuesta del admin queda afuera.
     */
    static final Map<String, Set<String>> ADMIN_RESPONSES = Map.ofEntries(
            Map.entry("OverviewResponse", Set.of()),
            Map.entry("TrafficResponse", Set.of()),
            Map.entry("Bucket", Set.of()),
            Map.entry("OverstaysResponse", Set.of()),
            Map.entry("Overstay", Set.of("subjectDocument")),
            Map.entry("LotResponse", Set.of("street", "number")),
            Map.entry("LotMemberResponse", Set.of("relation")),
            Map.entry("OnboardUserResponse", Set.of()),
            // US-54: un trabajador dado de alta solo con telefono no tiene email.
            Map.entry("PendingActivationResponse", Set.of("email")),
            // securepassb#121: sin DNI o sin email (alta solo con telefono);
            // suspension null si la persona no esta suspendida.
            Map.entry("PersonResponse", Set.of("documentNumber", "email", "suspension")),
            // Suspension anterior a #121: sin barrio, motivo ni fecha.
            Map.entry("SuspensionResponse", Set.of("neighborhoodName", "reason", "suspendedAt")),
            // securepassb#258: panel de la plataforma.
            Map.entry("NeighborhoodOverviewResponse", Set.of()),
            Map.entry("NeighborhoodAdminResponse", Set.of("email")),
            Map.entry("ProvisionedNeighborhoodResponse", Set.of()));

    @Bean
    public OpenAPI securepassOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Securepass API").version("1.0.0"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    public OpenApiCustomizer adminResponsesRequired() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            ADMIN_RESPONSES.forEach((name, nullable) -> {
                Schema<?> schema = schemas.get(name);
                if (schema == null || schema.getProperties() == null) {
                    throw new IllegalStateException("schema de respuesta del admin no encontrado: " + name);
                }
                schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                for (String field : nullable) {
                    Schema<?> property = schema.getProperties().get(field);
                    if (property == null) {
                        throw new IllegalStateException("campo nullable inexistente: " + name + "." + field);
                    }
                    if (property.get$ref() != null) {
                        // OpenAPI 3.0 ignora nullable al lado de un $ref: se
                        // envuelve en allOf para que el cliente lo tipe X | null.
                        Schema<Object> wrapped = new Schema<>();
                        wrapped.setAllOf(List.of(new Schema<>().$ref(property.get$ref())));
                        wrapped.setNullable(true);
                        schema.getProperties().put(field, wrapped);
                    } else {
                        property.setNullable(true);
                    }
                }
            });
        };
    }
}
