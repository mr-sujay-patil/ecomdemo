package com.ecomdemo.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientOptionsBuilderCustomizer;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The Redis client certificate follows the SSL bundle registry (Phase 36). {@code RedisClientAuthIT} proves the
 * whole path against the real Redis image; these tests pin down when the configuration applies and that the
 * managers Lettuce is given are the registry's current ones.
 */
@DisplayName("Redis client certificate auto-configuration (Phase 36)")
class RedisClientCertificateAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisClientCertificateAutoConfiguration.class));

    @Test
    @DisplayName("inactive without a Redis SSL bundle: compose and the tests keep Boot's own settings")
    void inactiveWithoutABundle() {
        contextRunner.withBean(SslBundles.class, () -> registry(bundle()))
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(LettuceClientOptionsBuilderCustomizer.class));
    }

    @Test
    @DisplayName("inactive in a service without Lettuce, even with the property: no Lettuce type is loaded")
    void inactiveWithoutLettuce() {
        contextRunner.withClassLoader(new FilteredClassLoader("io.lettuce.core"))
                .withPropertyValues("spring.data.redis.ssl.bundle=redis")
                .run(context -> assertThat(context).hasNotFailed()
                        .doesNotHaveBean(RedisClientCertificateAutoConfiguration.class));
    }

    @Test
    @DisplayName("with a Redis SSL bundle, every SSL context Lettuce builds asks the registry for its managers")
    void activeWithABundle() {
        CountingRegistry registry = new CountingRegistry();
        registry.registerBundle("redis", bundle());
        contextRunner.withBean(SslBundles.class, () -> registry)
                .withPropertyValues("spring.data.redis.ssl.bundle=redis")
                .run(context -> {
                    ClientOptions.Builder options = ClientOptions.builder();
                    context.getBean(LettuceClientOptionsBuilderCustomizer.class).customize(options);
                    SslOptions ssl = options.build().getSslOptions();

                    for (int connection = 1; connection <= 2; connection++) {
                        int before = registry.lookups.get();
                        ssl.createSslContextBuilder().build(); // what Lettuce does for every new connection
                        assertThat(registry.lookups.get()).as("connection " + connection).isGreaterThan(before);
                    }
                });
    }

    @Test
    @DisplayName("a bundle name that does not exist fails the start-up, by name")
    void unknownBundleFailsTheStart() {
        contextRunner.withBean(SslBundles.class, () -> registry(bundle()))
                .withPropertyValues("spring.data.redis.ssl.bundle=missing")
                .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("missing"));
    }

    @Test
    @DisplayName("the key and trust managers are those of the bundle registered now, not at start-up")
    void managersFollowTheRegistry() throws Exception {
        SslBundle atStart = bundle();
        DefaultSslBundleRegistry registry = registry(atStart);
        KeyManagerFactory keys = CurrentBundleManagers.keyManagerFactory(registry, "redis");
        TrustManagerFactory trust = CurrentBundleManagers.trustManagerFactory(registry, "redis");

        assertThat(keys.getKeyManagers()[0]).isSameAs(atStart.getManagers().getKeyManagers()[0]);
        assertThat(trust.getTrustManagers()[0]).isSameAs(atStart.getManagers().getTrustManagers()[0]);

        SslBundle renewed = bundle(); // what Boot registers when the files change (reload-on-update)
        registry.updateBundle("redis", renewed);

        assertThat(keys.getKeyManagers()[0]).isSameAs(renewed.getManagers().getKeyManagers()[0])
                .isNotSameAs(atStart.getManagers().getKeyManagers()[0]);
        assertThat(trust.getTrustManagers()[0]).isSameAs(renewed.getManagers().getTrustManagers()[0]);
        assertThat(keys).hasToString("the key managers of the current SSL bundle 'redis'");
    }

    /** A registry that counts how often a bundle is looked up. */
    private static final class CountingRegistry extends DefaultSslBundleRegistry {

        private final AtomicInteger lookups = new AtomicInteger();

        @Override
        public SslBundle getBundle(String name) {
            lookups.incrementAndGet();
            return super.getBundle(name);
        }
    }

    private static DefaultSslBundleRegistry registry(SslBundle bundle) {
        DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
        registry.registerBundle("redis", bundle);
        return registry;
    }

    /** A bundle whose managers are fixed objects, so a test can tell one bundle's from another's. */
    private static SslBundle bundle() {
        try {
            KeyStore empty = KeyStore.getInstance("PKCS12");
            empty.load(null, null);
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(empty, new char[0]);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init((KeyStore) null);
            SslManagerBundle managers = SslManagerBundle.of(keys, trust);
            return SslBundle.of(SslStoreBundle.NONE, null, null, null, managers);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
