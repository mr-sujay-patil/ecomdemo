package com.ecomdemo.assistant.support;

import com.ecomdemo.jwt.JwtProperties;
import com.ecomdemo.shared.TokenClaims;
import com.ecomdemo.support.RedisContainerConfig;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The assistant on a real port, with a real Redis, a scripted model and a fake store behind it.
 * Everything between the HTTP request and the model - security, retrieval, the prompt, tool
 * execution, the calls downstream, memory - is the production code.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import({RedisContainerConfig.class, AssistantIntegrationTest.Models.class})
@ActiveProfiles("it")
public abstract class AssistantIntegrationTest {

    protected static final FakeStore STORE = new FakeStore();

    @DynamicPropertySource
    static void pointAtTheFakeStore(DynamicPropertyRegistry properties) {
        properties.add("ecomdemo.assistant.catalog-base-url", STORE::baseUrl);
        properties.add("ecomdemo.assistant.app-base-url", STORE::baseUrl);
    }

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ScriptedChatModel model;

    @Autowired
    protected HashingEmbeddingModel embeddings;

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    private SecretKey jwtSigningKey;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void startClean() {
        STORE.reset();
        model.reset();
        embeddings.reset();
        Set<String> keys = redis.keys("assistant:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    protected String customer(long userId) {
        return token("customer" + userId, userId, "CUSTOMER");
    }

    protected String admin() {
        return token("boss", 1, "ADMIN");
    }

    protected String token(String username, long userId, String... roles) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(username)
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.expiry()))
                .claim(TokenClaims.USER_ID, userId)
                .claim(TokenClaims.ROLES, List.of(roles))
                .build();
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    protected ResponseEntity<String> post(String token, String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    protected ResponseEntity<String> chat(String token, String json) {
        return post(token, "/api/assistant/chat", json);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Models {

        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }

        @Bean
        HashingEmbeddingModel hashingEmbeddingModel() {
            return new HashingEmbeddingModel();
        }
    }
}
