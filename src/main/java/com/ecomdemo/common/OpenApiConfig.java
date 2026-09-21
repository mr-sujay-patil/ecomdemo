package com.ecomdemo.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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
                                - There is exactly one cart, shared by everybody. It is created on \
                                first use, and checkout empties it. Carts per user arrive with \
                                authentication in a later phase.
                                - Every failure returns the same shape, `{"status": <code>, \
                                "message": <text>}` — see the `ApiError` schema.
                                """)
                        .contact(new Contact().name("EcomDemo").url("https://github.com/mr-sujay-patil/ecomdemo"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("http://localhost:8080").description("Local development")));
    }
}
