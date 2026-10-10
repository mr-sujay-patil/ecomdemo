package com.ecomdemo.redis;

import java.security.KeyStore;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.KeyManagerFactorySpi;
import javax.net.ssl.ManagerFactoryParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.TrustManagerFactorySpi;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;

/**
 * Key and trust manager factories that always hand out the managers of the registry's CURRENT SSL bundle of
 * a name (Phase 36): the Redis counterpart of the outbox library's {@code CurrentSslBundle} (Phase 35).
 *
 * <p>Spring Boot gives Lettuce the {@link KeyManagerFactory} of the bundle it found at start-up. With
 * {@code reload-on-update}, a renewed certificate makes Boot register a NEW bundle under the same name, and
 * the old factory keeps the old key. Lettuce builds an SSL engine for every new connection and asks its
 * factory for the managers each time, so handing it these factories instead makes every new connection use
 * the certificate on disk now. Without them a renewal (day 60 of 90) would reach Redis only at the next
 * restart, and a pod that ran past day 90 would be refused on its next reconnect.
 *
 * <p>Initialising them does nothing: the bundle's own managers are already initialised.
 */
final class CurrentBundleManagers {

    private CurrentBundleManagers() {
    }

    static KeyManagerFactory keyManagerFactory(SslBundles bundles, String name) {
        KeyManagerFactory atStart = managers(bundles, name).getKeyManagerFactory();
        return new KeyManagerFactory(new KeyManagerFactorySpi() {
            @Override
            protected void engineInit(KeyStore keyStore, char[] password) {
            }

            @Override
            protected void engineInit(ManagerFactoryParameters parameters) {
            }

            @Override
            protected KeyManager[] engineGetKeyManagers() {
                return managers(bundles, name).getKeyManagers();
            }
        }, atStart.getProvider(), atStart.getAlgorithm()) {
            @Override
            public String toString() {
                return "the key managers of the current SSL bundle '" + name + "'";
            }
        };
    }

    static TrustManagerFactory trustManagerFactory(SslBundles bundles, String name) {
        TrustManagerFactory atStart = managers(bundles, name).getTrustManagerFactory();
        return new TrustManagerFactory(new TrustManagerFactorySpi() {
            @Override
            protected void engineInit(KeyStore keyStore) {
            }

            @Override
            protected void engineInit(ManagerFactoryParameters parameters) {
            }

            @Override
            protected TrustManager[] engineGetTrustManagers() {
                return managers(bundles, name).getTrustManagers();
            }
        }, atStart.getProvider(), atStart.getAlgorithm()) {
            @Override
            public String toString() {
                return "the trust managers of the current SSL bundle '" + name + "'";
            }
        };
    }

    private static SslManagerBundle managers(SslBundles bundles, String name) {
        return bundles.getBundle(name).getManagers();
    }
}
