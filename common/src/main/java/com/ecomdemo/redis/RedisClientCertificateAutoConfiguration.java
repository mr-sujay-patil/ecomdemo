package com.ecomdemo.redis;

import io.lettuce.core.SslOptions;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Keeps the certificate a service presents to Redis current after a renewal, without a restart (Phase 36).
 *
 * <p>In Kubernetes Redis requires a client certificate, which a service presents through the SSL bundle named
 * by {@code spring.data.redis.ssl.bundle} (its ConfigMap). Spring Boot builds Lettuce's TLS settings from that
 * bundle once, at start-up, and does not follow a reload. This replaces them, after Boot's own, with the same
 * settings whose key and trust managers come from the bundle registered NOW ({@link CurrentBundleManagers}).
 *
 * <p>An auto-configuration, not a component-scanned class, because {@code common} is on the classpath of
 * services without Redis: its conditions are read before the class is loaded, so those services never touch
 * a Lettuce type. Inactive when no Redis SSL bundle is set (compose, the tests).
 */
@AutoConfiguration(beforeName = "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration")
@ConditionalOnClass(name = {"io.lettuce.core.SslOptions",
        "org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer"})
@ConditionalOnProperty(RedisClientCertificateAutoConfiguration.BUNDLE_PROPERTY)
public class RedisClientCertificateAutoConfiguration {

    static final String BUNDLE_PROPERTY = "spring.data.redis.ssl.bundle";

    @Bean
    LettuceClientOptionsBuilderCustomizer currentRedisClientCertificate(SslBundles bundles, Environment environment) {
        SslOptions sslOptions = sslOptions(bundles, environment.getRequiredProperty(BUNDLE_PROPERTY));
        return builder -> builder.sslOptions(sslOptions);
    }

    /** What Boot sets from the bundle (managers, ciphers, protocols), with managers that follow a reload. */
    static SslOptions sslOptions(SslBundles bundles, String name) {
        SslBundle atStart = bundles.getBundle(name); // fails at start-up, by name, if the bundle does not exist
        SslOptions.Builder ssl = SslOptions.builder()
                .keyManager(CurrentBundleManagers.keyManagerFactory(bundles, name))
                .trustManager(CurrentBundleManagers.trustManagerFactory(bundles, name));
        org.springframework.boot.ssl.SslOptions options = atStart.getOptions();
        if (options.getCiphers() != null) {
            ssl.cipherSuites(options.getCiphers());
        }
        if (options.getEnabledProtocols() != null) {
            ssl.protocols(options.getEnabledProtocols());
        }
        return ssl.build();
    }
}
