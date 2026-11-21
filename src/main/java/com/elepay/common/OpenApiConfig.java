package com.elepay.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API contract metadata. The generated document is published at /v3/api-docs and committed as docs/openapi.json. */
@Configuration
class OpenApiConfig {
    @Bean
    OpenAPI elepayOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ELEPAY API").version("v1").description(
                        "Amounts are integer minor units. Financial requests require an Idempotency-Key header: an identical "
                                + "retry returns the original result and a changed request under the same key returns 409."))
                .components(new Components()
                        .addSecuritySchemes("oidc", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Elepay-Api-Key")))
                .addSecurityItem(new SecurityRequirement().addList("oidc"))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
