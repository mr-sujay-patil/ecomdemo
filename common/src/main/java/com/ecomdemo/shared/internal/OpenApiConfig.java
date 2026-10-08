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
import org.springframework.beans.factory.annotation.Value;
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
 * instead of the annotation because the values here are ordinary Java and can come from
 * configuration.
 *
 * <p><strong>Every service that scans this class publishes a document of its own</strong> (KI-001),
 * and the gateway's Swagger UI shows them side by side. So the parts that differ come from each
 * service's {@code application.properties}: {@code ecomdemo.openapi.title} says which service a
 * document belongs to, and {@code ecomdemo.openapi.summary} what it does. The conventions below
 * them are the same everywhere and stay here, written once. The only server is the gateway (see
 * {@link #DEFAULT_SERVER}): a client never calls a service's own port, and "Try it out" should not either.
 */
@Configuration
public class OpenApiConfig {

    /** The name operations refer to in {@code @SecurityRequirement(name = "bearerAuth")}. */
    public static final String BEARER_AUTH = "bearerAuth";

    /**
     * The only server in every document: RELATIVE, so it resolves against the address the document
     * was fetched from. Through the gateway's Swagger UI that is always the gateway, on whatever port
     * it is published (8080 in compose, https 18443 on kind), so "Try it out" goes through the same edge as
     * a client. An absolute {@code http://localhost:8080} was right for compose only.
     */
    static final String DEFAULT_SERVER = "/";

    private static final String CONVENTIONS =
            """
            **Conventions**

            - Every endpoint lives under `/api` and is reached through the gateway. A service's own \
            port is not for clients.
            - Money is a decimal with two places; totals are always calculated on the server and \
            never taken from the request.
            - Endpoints marked with a padlock need a token. Call `POST /api/auth/login` (the \
            **customer** document), copy the `accessToken`, and paste it into **Authorize** above - \
            then "Try it out" sends it as `Authorization: Bearer <token>`.
            - Every failure returns the same shape, `{"status": <code>, "message": <text>}` - see the \
            `ApiError` schema.
            """;

    @Bean
    public OpenAPI ecomdemoOpenApi(
            @Value("${ecomdemo.openapi.title:EcomDemo API}") String title,
            @Value("${ecomdemo.openapi.summary:A small e-commerce system.}") String summary,
            @Value("${ecomdemo.openapi.server-url:" + DEFAULT_SERVER + "}") String serverUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title(title)
                        .version("v1")
                        .description(summary + "\n\n" + CONVENTIONS)
                        .contact(new Contact().name("EcomDemo").url("https://github.com/mr-sujay-patil/ecomdemo"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url(serverUrl).description("The gateway this document was fetched through")))
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
