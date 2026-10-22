package com.winllc.innoutwork.export;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ExportFileNamesTest {

    @Test
    void lettersDigitsAndSingleDashesLowerCase() {
        assertThat(ExportFileNames.slug("Engineering / Team #2")).isEqualTo("engineering-team-2");
        assertThat(ExportFileNames.slug("  --Ops--  ")).isEqualTo("ops");
        assertThat(ExportFileNames.slug("Ünïcode only: ✓")).isEqualTo("n-code-only");
    }

    @Test
    void nothingLeftIsExport() {
        assertThat(ExportFileNames.slug(null)).isEqualTo("export");
        assertThat(ExportFileNames.slug("")).isEqualTo("export");
        assertThat(ExportFileNames.slug("---/// ")).isEqualTo("export");
    }

    @Test
    void atMostFortyCharacters() {
        assertThat(ExportFileNames.slug("a".repeat(100))).hasSize(40);
    }

    @Test
    @Timeout(2)
    void longRunsOfSeparatorsStayLinear() {
        assertThat(ExportFileNames.slug("-".repeat(200_000) + "x" + " -".repeat(200_000))).isEqualTo("x");
    }
}
