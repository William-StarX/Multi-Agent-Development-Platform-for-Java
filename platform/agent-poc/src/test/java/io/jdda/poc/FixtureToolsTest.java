package io.jdda.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FixtureToolsTest {
    @TempDir Path directory;

    @Test void rejectsExecutablePayloads() {
        for (String value : new String[] {"Runtime.getRuntime().exec(1)", "0; System.exit(0)",
                "discountCents++", "subtotalCents/*x*/", "new Object()", "\\u0030", "", "0".repeat(161)}) {
            assertThrows(IllegalArgumentException.class, () -> FixtureTools.validateExpression(value), value);
        }
        assertDoesNotThrow(() -> FixtureTools.validateExpression("Math.max(0L, subtotalCents - discountCents)"));
    }

    @Test void onlyChangesReturnExpressionAndLimitsEdits() throws Exception {
        Path source = directory.resolve(FixtureTools.SOURCE);
        Files.createDirectories(source.getParent());
        String original = "class X { long payable(long subtotalCents, long discountCents) { return subtotalCents + discountCents; } }";
        Files.writeString(source, original);
        var tools = new FixtureTools(directory);
        tools.replaceExpression("Math.max(0, subtotalCents - discountCents)");
        assertEquals(original.replace("subtotalCents + discountCents", "Math.max(0, subtotalCents - discountCents)"),
                Files.readString(source));
        tools.replaceExpression("0");
        assertThrows(IllegalStateException.class, () -> tools.replaceExpression("1"));
    }

    @Test void rejectsMissingSource() { assertThrows(java.io.IOException.class, () -> new FixtureTools(directory)); }

    @Test void budgetRejectsFifthCallBeforeHandler() {
        var budget = new CallBudget();
        budget.calls = 4;
        assertThrows(IllegalStateException.class, () -> budget.interceptModel(null, null));
        assertEquals(4, budget.calls);
    }
}
