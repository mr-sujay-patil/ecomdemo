package com.ecomdemo.auth.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The login throttling policy, under {@code ecomdemo.auth.throttle} (Phase 33).
 *
 * <p>The defaults: a USERNAME is blocked after 5 failures in 15 minutes (someone guessing one
 * person's password), a CLIENT address after 20 (someone trying many usernames: credential
 * stuffing). The first block lasts 30 seconds and doubles with every further failure, up to
 * 15 minutes. A block is temporary on purpose: a permanent lockout would let anyone lock any account
 * by typing its name wrong five times.
 */
@ConfigurationProperties(prefix = "ecomdemo.auth.throttle")
public record LoginThrottleProperties(
        Integer usernameLimit, Integer clientLimit, Duration window, Duration baseBlock, Duration maxBlock) {

    public LoginThrottleProperties {
        usernameLimit = usernameLimit == null ? 5 : usernameLimit;
        clientLimit = clientLimit == null ? 20 : clientLimit;
        window = window == null ? Duration.ofMinutes(15) : window;
        baseBlock = baseBlock == null ? Duration.ofSeconds(30) : baseBlock;
        maxBlock = maxBlock == null ? Duration.ofMinutes(15) : maxBlock;
    }
}
