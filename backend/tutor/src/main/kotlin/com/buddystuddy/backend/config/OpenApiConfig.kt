package com.buddystuddy.backend.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun buddyStuddyOpenApi(): OpenAPI {
        val bearerSchemeName = "accessToken"
        return OpenAPI()
            .components(
                Components().addSecuritySchemes(
                    bearerSchemeName,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Paste the BuddyStuddy access token. Swagger UI sends it as Authorization: Bearer <token>."),
                ),
            )
            .addSecurityItem(SecurityRequirement().addList(bearerSchemeName))
    }
}
