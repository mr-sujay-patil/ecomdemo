package com.ecomdemo.support;

import com.ecomdemo.common.GlobalExceptionHandler;
import com.ecomdemo.security.ApiErrorAccessDeniedHandler;
import com.ecomdemo.security.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.security.ApiErrorWriter;
import com.ecomdemo.security.SecurityConfig;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Puts this application's real security rules into a {@code @WebMvcTest} slice.
 *
 * <p>{@code @WebMvcTest} auto-configures Spring Security, but it does <em>not</em> pick up your
 * own {@code SecurityFilterChain}: a slice only scans web components, and a
 * {@code @Configuration} class is not one. Without this import the slice would run against
 * Spring Boot's fallback chain — "every request must be authenticated" — which answers 401 for
 * everything and therefore proves nothing about the rules that were actually written. A test
 * that passes against the wrong rules is the worst possible outcome here, so the real ones are
 * imported explicitly.
 *
 * <p>The three {@code ApiError*} classes come along because {@code SecurityConfig} is
 * constructed from them, and {@link GlobalExceptionHandler} because the 401 and 403 bodies are
 * only half its story — method-security denials are converted there instead.
 *
 * <p>Bundling them into one annotation rather than repeating a five-class {@code @Import} in
 * every slice also keeps the context cache key identical across those tests, so they share one
 * application context instead of building one each.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import({
    SecurityConfig.class,
    ApiErrorWriter.class,
    ApiErrorAuthenticationEntryPoint.class,
    ApiErrorAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
public @interface WithSecurityRules {
}
