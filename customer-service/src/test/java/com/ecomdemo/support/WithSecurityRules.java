package com.ecomdemo.support;

import com.ecomdemo.customer.SecurityConfig;
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
 * <p>A {@code @WebMvcTest} builds a permissive default chain unless told otherwise, so without this a
 * slice test proves the controller works for a caller who could never reach it. That matters more here
 * than anywhere else: this is the service where {@code /api/auth/login} must be open and everything
 * else must not, and getting that backwards is the difference between a shop and an incident.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Import({
    SecurityConfig.class,
    JwtKeyConfig.class,
    ApiErrorWriter.class,
    ApiErrorAuthenticationEntryPoint.class,
    ApiErrorAccessDeniedHandler.class,
    GlobalExceptionHandler.class
})
public @interface WithSecurityRules {
}
