package com.ecomdemo.outbox;

import com.ecomdemo.outbox.internal.OutboxConfig;
import com.ecomdemo.outbox.internal.OutboxPackageRegistrar;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Gives a service the transactional outbox, its relay, and the idempotent-consumer table.
 *
 * <p>Put it on the application class. The service must also:
 *
 * <ul>
 *   <li>create {@code outbox_event} and {@code processed_event} in a Flyway migration of its own
 *       (the DDL is in ecomdemo-app's V10 and V17, and notification-service's V1);
 *   <li>declare one {@link OutboxRoutes} bean saying which topic each of its events goes to.
 * </ul>
 *
 * <p>The {@code ecomdemo.outbox.*} properties are optional; the defaults are the values ecomdemo-app
 * has used since Phase 18 (poll every second, 100 rows a batch, keep published rows 7 days).
 *
 * <p>An annotation rather than auto-configuration that switches itself on from the classpath,
 * deliberately. A service taking part in the saga is a design decision, and it should be visible
 * on the class that starts the service, not implied by a line in a pom.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({OutboxConfig.class, OutboxPackageRegistrar.class})
public @interface EnableOutbox {
}
