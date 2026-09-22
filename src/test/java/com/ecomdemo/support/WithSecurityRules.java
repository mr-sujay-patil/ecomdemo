package com.ecomdemo.support;

import com.ecomdemo.shared.internal.GlobalExceptionHandler;
import com.ecomdemo.security.ApiErrorAccessDeniedHandler;
import com.ecomdemo.security.ApiErrorAuthenticationEntryPoint;
import com.ecomdemo.security.ApiErrorWriter;
import com.ecomdemo.security.JwtConfig;
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
 * only half its story — method-security denials and failed logins are converted there instead.
 * {@link JwtConfig} comes along because since Phase 9 the chain is a resource server, and a
 * resource server cannot be built without a {@code JwtDecoder}.
 *
 * <p>What deliberately does <em>not</em> come along is the {@code AuthenticationManager}: it
 * needs a {@code UserDetailsService}, which a web slice has none of. It lives in
 * {@code auth/AuthenticationManagerConfig} for exactly that reason.
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
    JwtConfig.class,
    ApiErrorWriter.class,
    ApiErrorAuthenticationEntryPoint.class,
    ApiErrorAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
public @interface WithSecurityRules {
}
