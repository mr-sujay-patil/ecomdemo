package com.ecomdemo.support;

import com.ecomdemo.catalog.SecurityConfig;
import com.ecomdemo.jwt.ApiErrorAccessDeniedHandler;
import com.ecomdemo.jwt.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.jwt.ApiErrorWriter;
import com.ecomdemo.jwt.JwtKeyConfig;
import com.ecomdemo.shared.internal.GlobalExceptionHandler;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Puts this service's REAL filter chain into a controller slice test.
 *
 * <p>A {@code @WebMvcTest} builds a permissive default chain unless told otherwise, so without this
 * a slice test proves the controller works for a caller who could never reach it. Importing the
 * actual {@link SecurityConfig} is what makes a 401 in a test mean a 401 in production.
 *
 * <p>{@link JwtKeyConfig} comes along because the chain is a resource server and one cannot be
 * built without a {@code JwtDecoder}. {@link GlobalExceptionHandler} comes along so that an error
 * raised in the slice has the same {@code ApiError} shape it would have anywhere else.
 *
 * <p>This is catalog-service's own copy rather than the application's: that one imports the
 * application's chain, which has a login endpoint, form rules and a {@code UserDetailsService} this
 * service does not have and must not pretend to.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import({
    SecurityConfig.class,
    JwtKeyConfig.class,
    // The three ApiError* beans, because the chain now names the resource server's OWN entry point -
    // without which a bad token comes back with an empty body. A slice test that does not import them
    // fails to build the context at all, which is the good kind of coupling: the test cannot pass
    // while describing a chain the service does not have.
    ApiErrorWriter.class,
    ApiErrorAuthenticationEntryPoint.class,
    ApiErrorAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
public @interface WithSecurityRules {
}
