package com.sdigital.platform.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3 metadata for the S-Digital Platform API.
 *
 * <p>Operation and schema details are derived from the controllers and DTOs. This class
 * only adds document-level {@code Info}, the bearer JWT security scheme (required by the
 * whole {@code /api/v1} API) and a light post-processing of the generated schemas.</p>
 */
@Configuration
public class OpenApiConfig {

    private static final String REF_PREFIX = "#/components/schemas/";
    private static final String NULL_SUFFIX = "null";
    private static final String BEARER_SCHEME = "bearer-jwt";

    @Bean
    public OpenAPI sDigitalOpenApi() {
        return new OpenAPI().info(new Info()
                .title("S-Digital Platform API")
                .description("""
                        Reusable backend foundation for the S-Digital platform.

                        This document covers the versioned REST API exposed under `/api/v1`.
                        Every error response follows the RFC 7807 `ProblemDetail` format.

                        Authentication is enforced with Keycloak: every `/api/v1` operation
                        requires a valid bearer JWT. Use the "Authorize" button to provide an
                        access token. The health probe and this documentation remain public.""")
                .version("v1"))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Keycloak-issued JWT access token, sent as `Authorization: Bearer <token>`.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * Post-processes the generated schemas to remove implementation details.
     *
     * <p>Spring Data types such as {@code Pageable} and {@code Sort} are reflected by
     * springdoc with the literal suffix {@code null} (generic type erased), producing
     * names like {@code Pageablenull} or {@code Sortnull}. This customizer renames such
     * schemas, repoints every {@code $ref} accordingly, and adds human-readable
     * descriptions. The JSON returned by the API is not affected.</p>
     */
    @Bean
    public OpenApiCustomizer paginationSchemaCustomizer() {
        return this::cleanUpSchemas;
    }

    private void cleanUpSchemas(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null || components.getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = components.getSchemas();

        Map<String, String> renames = collectRenames(schemas);
        if (!renames.isEmpty()) {
            applyRenames(schemas, renames);
        }
        describeSchemas(schemas);
    }

    private Map<String, String> collectRenames(Map<String, Schema> schemas) {
        Map<String, String> renames = new LinkedHashMap<>();
        for (String name : new ArrayList<>(schemas.keySet())) {
            if (name.endsWith(NULL_SUFFIX)) {
                String clean = name.substring(0, name.length() - NULL_SUFFIX.length());
                if (!clean.isBlank()) {
                    renames.put(name, clean);
                }
            }
        }
        return renames;
    }

    private void applyRenames(Map<String, Schema> schemas, Map<String, String> renames) {
        Map<String, Schema> rebuilt = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            Schema schema = entry.getValue();
            String newName = renames.getOrDefault(entry.getKey(), entry.getKey());
            schema.setName(newName);
            repointRefs(schema, renames);
            rebuilt.put(newName, schema);
        }
        schemas.clear();
        schemas.putAll(rebuilt);
    }

    private void repointRefs(Schema<?> schema, Map<String, String> renames) {
        if (schema == null) {
            return;
        }
        String ref = schema.get$ref();
        if (ref != null && ref.startsWith(REF_PREFIX)) {
            String oldName = ref.substring(REF_PREFIX.length());
            String newName = renames.get(oldName);
            if (newName != null) {
                schema.set$ref(REF_PREFIX + newName);
            }
        }
        if (schema.getProperties() != null) {
            for (Object property : schema.getProperties().values()) {
                if (property instanceof Schema<?> nested) {
                    repointRefs(nested, renames);
                }
            }
        }
        repointRefs(schema.getItems(), renames);
        repointRefsList(schema.getAllOf(), renames);
        repointRefsList(schema.getOneOf(), renames);
        repointRefsList(schema.getAnyOf(), renames);
    }

    private void repointRefsList(List<Schema> schemas, Map<String, String> renames) {
        if (schemas != null) {
            for (Schema schema : schemas) {
                repointRefs(schema, renames);
            }
        }
    }

    private void describeSchemas(Map<String, Schema> schemas) {
        describe(schemas.get("PageUserResponse"), "Envelope of a paginated list of users.", Map.ofEntries(
                Map.entry("content", "Users of the current page."),
                Map.entry("totalElements", "Total number of users across all pages."),
                Map.entry("totalPages", "Total number of pages available."),
                Map.entry("size", "Requested page size (maximum 100)."),
                Map.entry("number", "Zero-based index of the current page."),
                Map.entry("numberOfElements", "Number of users in the current page."),
                Map.entry("first", "Whether this is the first page."),
                Map.entry("last", "Whether this is the last page."),
                Map.entry("empty", "Whether the current page contains no user."),
                Map.entry("sort", "Sorting applied to the page."),
                Map.entry("pageable", "Pagination metadata of the page.")
        ));

        describe(schemas.get("Pageable"), "Pagination metadata of a page (Spring Data `Pageable`).", Map.of(
                "pageNumber", "Zero-based index of the page.",
                "pageSize", "Number of elements requested for the page.",
                "offset", "Offset of the first element of the page.",
                "paged", "Whether the page is paginated.",
                "unpaged", "Whether the page is unpaged.",
                "sort", "Sorting applied to the page."
        ));

        describe(schemas.get("Sort"), "Sorting information of a page (Spring Data `Sort`).", Map.of(
                "sorted", "Whether the sort is applied.",
                "unsorted", "Whether the sort is empty.",
                "empty", "Whether the sort holds no criterion."
        ));

        describeProblemDetail(schemas.get("ProblemDetail"));
    }

    private void describe(Schema<?> schema, String description, Map<String, String> propertyDescriptions) {
        if (schema == null) {
            return;
        }
        schema.setDescription(description);
        if (schema.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, String> entry : propertyDescriptions.entrySet()) {
            Object property = schema.getProperties().get(entry.getKey());
            if (property instanceof Schema<?> propertySchema) {
                propertySchema.setDescription(entry.getValue());
            }
        }
    }

    private void describeProblemDetail(Schema<?> problem) {
        if (problem == null || problem.getProperties() == null) {
            return;
        }
        problem.setDescription("RFC 7807 problem detail. Used by every error response of the API.");

        Schema<?> fieldError = new ObjectSchema()
                .addProperty("field", new StringSchema().description("Name of the field that failed validation."))
                .addProperty("message", new StringSchema().description("Validation message for the field."));

        Schema<?> errors = new ArraySchema()
                .items(fieldError)
                .description("Field-level validation errors. Present only on 400 responses caused by request validation.");
        problem.addProperty("errors", errors);
    }
}
