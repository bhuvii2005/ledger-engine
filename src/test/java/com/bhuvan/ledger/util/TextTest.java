package com.bhuvan.ledger.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void nullStaysNull() {
        assertThat(Text.cleanOptional(null)).isNull();
    }

    @Test
    void controlCharactersAreRemovedAndEdgesTrimmed() {
        assertThat(Text.cleanOptional("  pay\u0007day\n\t ")).isEqualTo("payday");
    }

    @Test
    void blankOrControlOnlyBecomesNull() {
        assertThat(Text.cleanOptional("   ")).isNull();
        assertThat(Text.cleanOptional("\u0000\u001f")).isNull();
    }

    @Test
    void ordinaryTextIsUntouched() {
        assertThat(Text.cleanOptional("rent | march, ₹ 1,000")).isEqualTo("rent | march, ₹ 1,000");
    }
}
