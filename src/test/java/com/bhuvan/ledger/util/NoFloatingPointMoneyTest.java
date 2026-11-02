package com.bhuvan.ledger.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class NoFloatingPointMoneyTest {

    // a type usage, not something like CoercionInputShape.Float
    private static final Pattern FLOATING_TYPE = Pattern.compile("(?<![.\\w])(double|float|Double|Float|BigDecimal)\\b");
    // only code counts: prose like "Double-entry" in a string or comment is fine
    private static final Pattern STRINGS_AND_COMMENTS =
            Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    @Test
    void mainSourcesNeverUseFloatingPointTypes() throws IOException {
        List<String> offenders;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            offenders = files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> usesFloatingType(read(p)))
                    .map(Path::toString)
                    .toList();
        }
        assertThat(offenders).as("files using double/float/BigDecimal").isEmpty();
    }

    @Test
    void detectorFlagsRealUsageButIgnoresProse() {
        assertThat(usesFloatingType("double fee = 0;")).isTrue();
        assertThat(usesFloatingType("List<Double> fees;")).isTrue();
        assertThat(usesFloatingType("var x = new BigDecimal(\"1.0\");")).isTrue();
        assertThat(usesFloatingType("long x = (long) (float) y;")).isTrue();

        assertThat(usesFloatingType("String s = \"Double-entry ledger\";")).isFalse();
        assertThat(usesFloatingType("// never use double here")).isFalse();
        assertThat(usesFloatingType("/* BigDecimal is banned */ long paise;")).isFalse();
        assertThat(usesFloatingType("shape(CoercionInputShape.Float);")).isFalse();
    }

    private static boolean usesFloatingType(String source) {
        String code = STRINGS_AND_COMMENTS.matcher(source).replaceAll(" ");
        return FLOATING_TYPE.matcher(code).find();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
