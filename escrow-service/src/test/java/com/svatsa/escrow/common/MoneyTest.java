package com.svatsa.escrow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Display formatting of paise with integer arithmetic only. NotificationConsumer used to compute
 * {@code paise / 100.0} as a double and print it with %.2f.
 */
@DisplayName("Money.formatPaise")
class MoneyTest {

    @ParameterizedTest(name = "{0} paise -> {1}")
    @CsvSource(delimiter = '|', value = {
        "0                    | ₹0.00",
        "5                    | ₹0.05",
        "100                  | ₹1.00",
        "99999                | ₹999.99",
        "100000               | ₹1,000.00",
        "10000000             | ₹1,00,000.00",      // 1,00,00,000 paise = one lakh rupees
        "1234567890123        | ₹12,34,56,78,901.23",
        "-150                 | -₹1.50",
        "9223372036854775807  | ₹92,23,37,20,36,85,47,758.07",
        "-9223372036854775808 | -₹92,23,37,20,36,85,47,758.08",
    })
    void formatsWithIndianGroupingAndTwoPaiseDigits(long paise, String expected) {
        assertThat(Money.formatPaise(paise)).isEqualTo(expected);
    }
}
