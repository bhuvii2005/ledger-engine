package com.bhuvan.ledger.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private static final UUID TXN = UUID.fromString("3f1c2b7a-0000-4000-8000-000000000001");

    private static String transfer(long paise, String description) {
        return RequestHasher.hash("TRANSFER", "ACC1000000001", "ACC1000000002", paise, "INR", description, null);
    }

    @Test
    void matchesTheDocumentedCanonicalFormat() {
        // sha256("TRANSFER|ACC1000000001|ACC1000000002|100000|INR|rent|"), computed outside Java
        assertThat(transfer(100000, "rent"))
                .isEqualTo("c8ffb26f7637c154e326d601b3184df7103317aa400d07db546ce548526d32de");
    }

    @Test
    void sameRequestHashesTheSame() {
        assertThat(transfer(100000, "rent")).isEqualTo(transfer(100000, "rent")).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void amountWithAndWithoutDecimalsHashesTheSame() {
        assertThat(transfer(Money.parse("1000"), "rent")).isEqualTo(transfer(Money.parse("1000.00"), "rent"));
    }

    @Test
    void nullAndEmptyDescriptionHashTheSame() {
        assertThat(transfer(100, null)).isEqualTo(transfer(100, ""));
    }

    @Test
    void differentOperationHashesDifferently() {
        String deposit = RequestHasher.hash("DEPOSIT", "ACC1000000001", "ACC1000000002", 100, "INR", null, null);
        assertThat(deposit).isNotEqualTo(transfer(100, null));
    }

    @Test
    void everyFieldChangesTheHash() {
        String base = RequestHasher.hash("REVERSAL", "A", "B", 100, "INR", "d", TXN);
        assertThat(RequestHasher.hash("REVERSAL", "X", "B", 100, "INR", "d", TXN)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("REVERSAL", "A", "X", 100, "INR", "d", TXN)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("REVERSAL", "A", "B", 101, "INR", "d", TXN)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("REVERSAL", "A", "B", 100, "USD", "d", TXN)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("REVERSAL", "A", "B", 100, "INR", "e", TXN)).isNotEqualTo(base);
        assertThat(RequestHasher.hash("REVERSAL", "A", "B", 100, "INR", "d", UUID.randomUUID())).isNotEqualTo(base);
        assertThat(RequestHasher.hash("REVERSAL", "A", "B", 100, "INR", "d", null)).isNotEqualTo(base);
    }

    @Test
    void pipeInsideDescriptionCannotForgeAnotherFieldBoundary() {
        String withPipe = RequestHasher.hash("REVERSAL", "A", "B", 100, "INR", "x|" + TXN, null);
        String withTxn = RequestHasher.hash("REVERSAL", "A", "B", 100, "INR", "x", TXN);
        assertThat(withPipe).isNotEqualTo(withTxn);
    }

    @Test
    void sourceAndDestinationAreNotInterchangeable() {
        String ab = RequestHasher.hash("TRANSFER", "A", "B", 100, "INR", null, null);
        String ba = RequestHasher.hash("TRANSFER", "B", "A", 100, "INR", null, null);
        assertThat(ab).isNotEqualTo(ba);
    }
}
