package com.smmpanel.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.Arrays;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger Configuration Implements API documentation best practices from Stack Overflow
 *
 * <p>Provides: - Versioned API documentation - Multiple security scheme support (JWT and API Key) -
 * Grouped API endpoints by version and purpose - Comprehensive API standards documentation
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_JWT = "bearerAuth";
    private static final String SECURITY_SCHEME_API_KEY = "apiKey";
    private static final String API_TITLE = "SMM Panel API";
    private static final String API_VERSION = "2.0.0";

    @Value("${server.port:8080}")
    private String serverPort;

    private static final String API_DESCRIPTION =
            """
        API documentation for SMM Panel - Social Media Marketing Platform

        ## API Standards (Stack Overflow Best Practices)

        ### Resource Naming
        - **Collections**: Use plural forms (orders, users, services)
        - **Singletons**: Use singular forms (profile, health, auth)
        - **Format**: Use kebab-case for multi-word resources (balance-transactions)

        ### HTTP Methods
        - **GET**: Retrieve resource(s), idempotent operation
        - **POST**: Create new resource or perform non-idempotent action
        - **PUT**: Full update/replace of existing resource
        - **PATCH**: Partial update of existing resource
        - **DELETE**: Remove resource

        ### API Versioning
        - **URL Path**: Primary versioning via /api/v1, /api/v2
        - **Header**: Optional X-API-Version header support
        - **Content Type**: Accept: application/vnd.smmpanel.v1+json

        ### Authentication
        - **JWT**: Bearer token in Authorization header
        - **API Key**: X-API-Key header for programmatic access

        ### Response Format
        ```json
        {
          "data": {},
          "meta": {
            "page": 0,
            "size": 20,
            "total": 100
          },
          "links": {
            "self": "/api/v1/orders?page=0",
            "next": "/api/v1/orders?page=1"
          }
        }
        ```

        ### Error Format
        ```json
        {
          "error": "Validation failed",
          "code": "VALIDATION_ERROR",
          "timestamp": "2024-01-01T00:00:00Z",
          "details": {
            "field": "error message"
          }
        }
        ```

        ### Rate Limiting
        - Standard endpoints: 100 requests/minute
        - Search endpoints: 30 requests/minute
        - Bulk operations: 10 requests/minute

        ### Status Codes
        - **200 OK**: Successful GET, PUT, PATCH
        - **201 Created**: Successful POST creating resource
        - **204 No Content**: Successful DELETE
        - **400 Bad Request**: Client error
        - **401 Unauthorized**: Missing/invalid authentication
        - **403 Forbidden**: Authenticated but not authorized
        - **404 Not Found**: Resource doesn't exist
        - **422 Unprocessable**: Validation errors
        - **429 Too Many Requests**: Rate limit exceeded
        - **500 Internal Error**: Server error
        """;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .servers(servers())
                .security(
                        Arrays.asList(
                                new SecurityRequirement().addList(SECURITY_SCHEME_JWT),
                                new SecurityRequirement().addList(SECURITY_SCHEME_API_KEY)))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        SECURITY_SCHEME_JWT,
                                        new SecurityScheme()
                                                .name(SECURITY_SCHEME_JWT)
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description("JWT Bearer token authentication"))
                                .addSecuritySchemes(
                                        SECURITY_SCHEME_API_KEY,
                                        new SecurityScheme()
                                                .name(SECURITY_SCHEME_API_KEY)
                                                .type(SecurityScheme.Type.APIKEY)
                                                .in(SecurityScheme.In.HEADER)
                                                .name("X-API-Key")
                                                .description(
                                                        "API Key authentication for programmatic"
                                                                + " access")))
                .info(
                        new Info()
                                .title(API_TITLE)
                                .description(API_DESCRIPTION)
                                .version(API_VERSION)
                                .contact(
                                        new Contact()
                                                .name("SMM Panel API Support")
                                                .email("api-support@smmpanel.com")
                                                .url("https://smmpanel.com/api-docs"))
                                .license(
                                        new License()
                                                .name("Proprietary")
                                                .url("https://smmpanel.com/license")));
    }

    private List<Server> servers() {
        return Arrays.asList(
                new Server()
                        .url("http://localhost:" + serverPort)
                        .description("Local Development Server"),
                new Server().url("https://api.smmpanel.com").description("Production Server"),
                new Server().url("https://staging-api.smmpanel.com").description("Staging Server"));
    }

    /** Group APIs by version (Stack Overflow best practice for versioning) */
    @Bean
    public GroupedOpenApi apiV1() {
        return GroupedOpenApi.builder()
                .group("v1-apis")
                .displayName("API Version 1")
                .pathsToMatch("/api/v1/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin-apis")
                .displayName("Admin APIs")
                .pathsToMatch("/api/admin/**", "/api/v*/admin/**")
                .addOpenApiCustomizer(
                        openApi ->
                                openApi.info(
                                        new Info()
                                                .title("Admin API")
                                                .description(
                                                        "Administrative endpoints requiring ADMIN"
                                                                + " role")))
                .build();
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder()
                .group("public-apis")
                .displayName("Public APIs")
                .pathsToMatch("/api/v*/auth/**", "/api/v*/health/**", "/api/public/**")
                .addOpenApiCustomizer(
                        openApi ->
                                openApi.info(
                                        new Info()
                                                .title("Public API")
                                                .description(
                                                        "Publicly accessible endpoints (no"
                                                                + " authentication required)")))
                .build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder()
                .group("internal-apis")
                .displayName("Internal APIs")
                .pathsToMatch("/api/internal/**", "/actuator/**")
                .addOpenApiCustomizer(
                        openApi ->
                                openApi.info(
                                        new Info()
                                                .title("Internal API")
                                                .description(
                                                        "Internal system endpoints"
                                                            + " (system-to-system communication)")))
                .build();
    }
}
