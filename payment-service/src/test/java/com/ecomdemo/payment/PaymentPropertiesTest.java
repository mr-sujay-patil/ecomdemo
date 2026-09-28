package com.ecomdemo.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PaymentProperties")
class PaymentPropertiesTest {

    @Test
    @DisplayName("defaults the limit to 10,000.00 when none is configured")
    void defaults() {
        assertThat(new PaymentProperties(null).declineAbove()).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("refuses a negative limit, which would decline every order")
    void refusesNegative() {
        assertThatThrownBy(() -> new PaymentProperties(new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
