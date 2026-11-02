package com.bhuvan.ledger.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest
    @CsvSource({
            "1000, 100000",
            "1000.5, 100050",
            "1000.50, 100050",
            "0.01, 1",
            "0.1, 10",
            "1, 100",
            "00012.30, 1230",
            "99999999.99, 9999999999"
    })
    void parsesValidAmountsToPaise(String input, long expectedPaise) {
        assertThat(Money.parse(input)).isEqualTo(expectedPaise);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0", "0.00", "0.0", "00", "-1", "-0", "-0.00", "+5",
            "10.001", "1e3", "1E3", "1,000", " 100", "100 ", " ",
            "NaN", "Infinity", ".5", "5.", "123456789", "1.2.3", "abc",
            "１００" // full-width digits must not pass as \d
    })
    void rejectsInvalidAmounts(String input) {
        assertThatThrownBy(() -> Money.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void rejectsNullAndEmpty(String input) {
        assertThatThrownBy(() -> Money.parse(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0.00",
            "1, 0.01",
            "10, 0.10",
            "100, 1.00",
            "100050, 1000.50",
            "100000, 1000.00",
            "-150, -1.50",
            "-5, -0.05",
            "9223372036854775807, 92233720368547758.07",
            "-9223372036854775808, -92233720368547758.08"
    })
    void formatsWithExactlyTwoDecimals(long paise, String expected) {
        assertThat(Money.format(paise)).isEqualTo(expected);
    }

    @Test
    void parseAndFormatRoundTrip() {
        assertThat(Money.format(Money.parse("1000.5"))).isEqualTo("1000.50");
        assertThat(Money.format(Money.parse("7"))).isEqualTo("7.00");
    }

    @Test
    void addThrowsOnOverflowInsteadOfWrapping() {
        assertThatThrownBy(() -> Money.add(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
        assertThat(Money.add(100, 50)).isEqualTo(150);
    }

    @Test
    void subtractThrowsOnOverflowInsteadOfWrapping() {
        assertThatThrownBy(() -> Money.subtract(Long.MIN_VALUE, 1)).isInstanceOf(ArithmeticException.class);
        assertThat(Money.subtract(100, 150)).isEqualTo(-50);
    }
}
