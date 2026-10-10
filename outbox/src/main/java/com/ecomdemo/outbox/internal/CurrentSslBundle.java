package com.ecomdemo.outbox.internal;

import javax.net.ssl.SSLContext;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;

/**
 * An SSL bundle that is always the registry's CURRENT bundle of that name (Phase 35).
 *
 * <p>Spring Boot hands a Kafka client the {@link SslBundle} object it found when the client factory was
 * built. With {@code reload-on-update}, a renewed certificate makes Boot register a NEW bundle object under
 * the same name; the old object keeps the old certificate. Boot's Kafka engine factory builds an
 * {@link SSLContext} from its bundle for every new connection, so giving it this view instead makes every
 * new connection use the certificate on disk now. Without it, a renewal (day 60 of 90) would reach the Kafka
 * clients only on the next restart, and a pod that ran past day 90 would fail on its next reconnect.
 */
final class CurrentSslBundle implements SslBundle {

    private final SslBundles bundles;
    private final String name;

    CurrentSslBundle(SslBundles bundles, String name) {
        this.bundles = bundles;
        this.name = name;
    }

    private SslBundle current() {
        return bundles.getBundle(name);
    }

    @Override
    public SslStoreBundle getStores() {
        return current().getStores();
    }

    @Override
    public SslBundleKey getKey() {
        return current().getKey();
    }

    @Override
    public SslOptions getOptions() {
        return current().getOptions();
    }

    @Override
    public String getProtocol() {
        return current().getProtocol();
    }

    @Override
    public SslManagerBundle getManagers() {
        return current().getManagers();
    }

    /** One bundle for the whole context, so the managers and the protocol cannot come from two versions. */
    @Override
    public SSLContext createSslContext() {
        return current().createSslContext();
    }

    @Override
    public String toString() {
        return "the current SSL bundle '" + name + "'";
    }
}
