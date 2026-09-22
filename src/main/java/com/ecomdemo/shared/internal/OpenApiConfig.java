package com.ecomdemo.shared.internal;

import com.ecomdemo.shared.ApiError;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document-level half of the OpenAPI description: title, version, servers.
 *
 * <p>springdoc derives everything else — paths, parameters, schemas, required fields — by
 * scanning the controllers and the DTO records at startup. That is the <em>code-first</em>
 * approach: the code is the source of truth and the specification is generated from it, so the
 * two cannot drift apart. The alternative, <em>contract-first</em>, is to hand-write the YAML
 * specification and generate stubs from it; it is the better fit once several teams must agree
 * on an API before any of them writes code. This project has one codebase and one author, so
 * code-first wins.
 *
 * <p>This bean is the code equivalent of {@code @OpenAPIDefinition} on a class. A bean is used
 * instead of the annotation because the values here are ordinary Java and can later come from
 * configuration or the build (the version, for instance, once releases are tagged).
 */
@Configuration
public class OpenApiConfig {

    /** The name operations refer to in {@code @SecurityRequirement(name = "bearerAuth")}. */
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI ecomdemoOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("EcomDemo API")
                        .version("v1")
                        .description(
                                """
                                A small e-commerce API: a product catalogue, one shared cart, and \
                                checkout.

                                **Conventions**

                                - Every endpoint lives under `/api`.
                                - Money is a decimal with two places; totals are always calculated \
                                on the server and never taken from the request.
                                - Every account has its own cart and its own orders. A cart is \
                                created on first use, and checkout empties it.
                                - Browsing the catalogue is open to anyone. Changing it requires \
                                an ADMIN; the cart and orders require a CUSTOMER.
                                - Everything else needs a token. Call `POST /api/auth/login`, copy \
                                the `accessToken`, and paste it into **Authorize** above — then \
                                "Try it out" sends it as `Authorization: Bearer <token>`.
                                - Every failure returns the same shape, `{"status": <code>, \
                                "message": <text>}` — see the `ApiError` schema.
                                """)
                        .contact(new Contact().name("EcomDemo").url("https://github.com/mr-sujay-patil/ecomdemo"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("http://localhost:8080").description("Local development")))
                // Declaring the scheme is what puts the "Authorize" button in Swagger UI and
                // makes "Try it out" attach an Authorization header. Individual operations opt
                // into it with @SecurityRequirement("bearerAuth"); the ones without it — browsing
                // the catalogue, registering, logging in — are the genuinely public endpoints, so
                // the document doubles as a readable statement of what needs an account.
                //
                // `bearerFormat` is documentation rather than configuration: it tells a reader
                // (and a code generator) that the opaque-looking string is a JWT, which is why
                // Swagger UI asks for a token here instead of a username and password.
                .components(new Components()
                        .addSecuritySchemes(
                                BEARER_AUTH,
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description(
                                                "A signed JWT from POST /api/auth/login, sent as "
                                                        + "`Authorization: Bearer <token>`. \"Bearer\" is "
                                                        + "meant literally: whoever holds it is the "
                                                        + "account, so it is only safe over TLS or, as "
                                                        + "here, on localhost. It expires after 15 "
                                                        + "minutes and cannot be revoked before then.")));
    }
}
