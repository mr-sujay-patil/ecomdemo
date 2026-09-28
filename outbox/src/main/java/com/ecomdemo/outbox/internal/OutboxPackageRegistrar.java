package com.ecomdemo.outbox.internal;

import com.ecomdemo.outbox.Outbox;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

/**
 * Adds the outbox's package to the ones Boot searches for JPA entities and repositories - but only
 * when the application's own package does not already contain it.
 *
 * <p>Boot looks for entities and repositories under the application class's package. That misses
 * the outbox for inventory-service ({@code com.ecomdemo.inventory}) and payment-service, so the
 * package has to be ADDED. But ecomdemo-app's class sits in {@code com.ecomdemo}, which contains
 * {@code com.ecomdemo.outbox} already, and registering it a second time makes Spring Data find
 * every outbox repository twice and refuse to start. That was the first thing the build said when
 * this was a plain {@code @AutoConfigurationPackage}.
 *
 * <p>Imported by {@code @EnableOutbox} directly, not by {@link OutboxConfig}, so that the metadata
 * this registrar receives is the APPLICATION class's - the one whose package decides.
 */
public class OutboxPackageRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata application, BeanDefinitionRegistry registry) {
        String applicationPackage = ClassUtils.getPackageName(application.getClassName());
        String outboxPackage = Outbox.class.getPackageName();
        if (!covers(applicationPackage, outboxPackage)) {
            AutoConfigurationPackages.register(registry, outboxPackage);
        }
    }

    static boolean covers(String applicationPackage, String outboxPackage) {
        return outboxPackage.equals(applicationPackage)
                || outboxPackage.startsWith(applicationPackage + ".");
    }
}
