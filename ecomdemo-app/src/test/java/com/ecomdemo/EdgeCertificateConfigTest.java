package com.ecomdemo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.support.ProjectRoot;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The edge certificate names every host the cluster's Traefik serves (KI-060).
 *
 * <p>Traefik terminates HTTPS for two Ingresses in the {@code ecomdemo} namespace: ours (no host, so
 * {@code localhost}) and the frontend team's shop ({@code shop.localhost}). Both use the one
 * certificate cert-manager issues into {@code ecomdemo-tls}. KI-051 named only {@code localhost} and
 * {@code 127.0.0.1} in it, so every client refused the shop's HTTPS (web KI-034).
 *
 * <p>This is the fast half. {@code scripts/k8s-smoke.sh} is the proof on a running cluster: it makes a
 * TLS handshake as {@code shop.localhost} and verifies the certificate against the local CA.
 */
@DisplayName("The edge certificate names every host Traefik serves (KI-060)")
class EdgeCertificateConfigTest {

    private static final String SHOP_HOST = "shop.localhost";

    @Test
    @DisplayName("the certificate names the frontend team's shop host")
    void certificateNamesTheShopHost() throws IOException {
        assertThat(certificateDnsNames())
                .as("The shop is served at https://%s:18443 by the same Traefik; a certificate without that name "
                        + "is refused by every client", SHOP_HOST)
                .contains("localhost", SHOP_HOST);
    }

    @Test
    @DisplayName("every host in the Ingress's tls section is named by the certificate")
    void ingressTlsHostsAreInTheCertificate() throws IOException {
        assertThat(ingressTlsHosts()).contains(SHOP_HOST);
        assertThat(certificateDnsNames()).containsAll(ingressTlsHosts());
    }

    @SuppressWarnings("unchecked")
    private static List<String> certificateDnsNames() throws IOException {
        Map<String, Object> spec = (Map<String, Object>) load("k8s/tls-certificate.yaml").get("spec");
        return (List<String>) spec.get("dnsNames");
    }

    @SuppressWarnings("unchecked")
    private static List<String> ingressTlsHosts() throws IOException {
        Map<String, Object> spec = (Map<String, Object>) load("k8s/ingress.yaml").get("spec");
        List<Map<String, Object>> tls = (List<Map<String, Object>>) spec.get("tls");
        return tls.stream().flatMap(entry -> ((List<String>) entry.get("hosts")).stream()).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String path) throws IOException {
        try (Reader reader = Files.newBufferedReader(ProjectRoot.resolve(path))) {
            return (Map<String, Object>) new Yaml().load(reader);
        }
    }
}
