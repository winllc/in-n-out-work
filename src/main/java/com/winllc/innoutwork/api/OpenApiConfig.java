package com.winllc.innoutwork.api;

import com.winllc.innoutwork.config.ApplicationProperties;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * The OpenAPI 3 document of /api/v1, generated from the controllers (springdoc) and served at
 * {@code /api/v1/openapi} (JSON) and {@code /api/v1/openapi.yaml}. Its server is the configured public base URL
 * (application.application-base-url), never one taken from the request's Host or Forwarded headers.
 */
@Configuration
public class OpenApiConfig {

    static final String SCHEME = "apiToken";

    @Bean
    OpenAPI readyRoomOpenApi(ApplicationProperties properties) {
        return new OpenAPI()
                .info(new Info()
                        .title("ReadyRoom API")
                        .version("1")
                        .description("""
                                Read-only access to ReadyRoom for HR, payroll and workforce systems: the people \
                                ReadyRoom tracks and their status, attendance records, and absences and leave.

                                Every call needs an API token (Authorization: Bearer ensapi_...). An administrator \
                                creates it under Administration, Integrations, chooses its scopes, expiry and rate \
                                limit, and sees it once. A token reads what that administrator may read at the time \
                                of the call, and never more. Past its rate limit a call gets 429 with Retry-After. \
                                Times are ISO-8601 with an offset, in each person's time zone.

                                Signed webhooks for absence alerts and status changes are described in the README \
                                (Public API and webhooks)."""))
                .servers(List.of(new Server().url(trimSlash(properties.getApplicationBaseUrl()))
                        .description("This ReadyRoom")))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("ensapi_ token")
                        .description("An API token, created by an administrator and shown once")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }

    /** The refusals every endpoint can answer with, as ApiError. */
    @Bean
    OpenApiCustomizer standardApiResponses() {
        return openApi -> {
            Map<String, Schema> schemas = ModelConverters.getInstance().read(ApiError.class);
            schemas.forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
            Content error = new Content().addMediaType("application/json",
                    new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiError")));
            Map<String, String> standard = Map.of(
                    "400", "A parameter is missing or wrong",
                    "401", "No token, or a malformed, unknown, revoked or expired one",
                    "403", "The token lacks the scope, its owner may not see that person, or the feature is not licensed",
                    "429", "Over the token's rate limit; see Retry-After");
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation ->
                    standard.forEach((code, description) -> operation.getResponses()
                            .computeIfAbsent(code, c -> new ApiResponse().description(description).content(error)))));
        };
    }

    static String trimSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
