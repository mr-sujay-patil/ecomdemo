package com.ecomdemo.assistant;

import com.ecomdemo.support.TestJwt;
import static org.assertj.core.api.Assertions.assertThat;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import com.ecomdemo.support.RedisContainerConfig;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.ActiveProfiles;

/**
 * The default: no models at all. The service must start (it is in compose and Kubernetes with
 * {@code AI_CHAT_PROVIDER=none}) and answer every chat with a 503 that says how to set it up.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(RedisContainerConfig.class)
@ActiveProfiles("it")
@DisplayName("assistant with no model configured")
class AssistantNotConfiguredIT {

    @Autowired
    private TestRestTemplate rest;


    @Autowired
    private JwtProperties jwtProperties;

    @Test
    @DisplayName("starts, and a chat is a 503 naming both settings, with a Retry-After")
    void chatSaysHowToSetItUp() {
        ResponseEntity<String> response = post("/api/assistant/chat", "{\"message\":\"Do you ship abroad?\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("AI_CHAT_PROVIDER").contains("AI_EMBEDDING_PROVIDER");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("300");
    }

    @Test
    @DisplayName("confirming needs no model: an unknown proposal is still an ordinary 404")
    void confirmationWorksWithoutAModel() {
        assertThat(post("/api/assistant/actions/does-not-exist/confirm", "").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> post(String path, String json) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject("customer")
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.expiry()))
                .claim(TokenClaims.USER_ID, 11)
                .claim(TokenClaims.ROLES, List.of("CUSTOMER"))
                .build();
        String token = TestJwt.sign(claims);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }
}
