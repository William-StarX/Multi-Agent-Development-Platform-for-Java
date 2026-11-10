package io.jdda.poc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public final class FixtureTools {
    static final String SOURCE = "src/main/java/demo/PriceCalculator.java";
    private final Path workspace;
    final List<String> actions = new ArrayList<>();

    FixtureTools(Path workspace) throws IOException {
        this.workspace = workspace.toRealPath();
        source();
    }

    private Path source() throws IOException {
        Path file = workspace.resolve(SOURCE);
        Path part = workspace;
        for (Path segment : Path.of(SOURCE)) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part)) throw new IOException("Symbolic links are not allowed.");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().startsWith(workspace)) throw new IOException("Invalid fixture path.");
        return file;
    }

    @Tool(description = "Read the only editable Java file and its required behavior.")
    public String readFixture() throws IOException {
        actions.add("readFixture");
        return "For nonnegative long inputs, payable is subtotal minus discount, floored at zero. "
                + "Tests include 1000/200 -> 800, 100/200 -> 0, equal amounts -> 0, and long amounts.\n"
                + Files.readString(source());
    }

    static void validateExpression(String expression) {
        if (expression == null || expression.length() > 160 || expression.isBlank())
            throw new IllegalArgumentException("Expression must contain 1 to 160 characters.");
        // A token allowlist, not an arbitrary Java source or command interface.
        String rest = expression.replaceAll("Math\\.max|Math\\.min|subtotalCents|discountCents", "");
        if (!rest.matches("[0-9L\\s()+,*/-]*") || rest.contains("//") || rest.contains("/*")
                || rest.contains("++") || rest.contains("--"))
            throw new IllegalArgumentException("Only the two inputs, integer literals, Math.max/min and arithmetic are allowed.");
    }

    @Tool(description = "Replace only the payable return expression. Allowed: subtotalCents, discountCents, "
            + "integer literals, arithmetic, parentheses and Math.max/min. Do not include return or a semicolon.")
    public String replaceExpression(@ToolParam(description = "Java long expression") String expression) throws IOException {
        validateExpression(expression);
        if (actions.stream().filter("replaceExpression"::equals).count() >= 2)
            throw new IllegalStateException("Edit limit reached.");
        Path file = source();
        String original = Files.readString(file);
        var matcher = java.util.regex.Pattern.compile("return [^;\\r\\n]+;").matcher(original);
        if (!matcher.find()) throw new IOException("Expected one return statement.");
        String changed = matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement("return " + expression + ";"));
        Files.writeString(file, changed);
        actions.add("replaceExpression");
        return "Expression saved. The trusted runner will execute protected Maven tests and commit only if they pass.";
    }
}
