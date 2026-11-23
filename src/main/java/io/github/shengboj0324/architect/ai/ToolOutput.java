package io.github.shengboj0324.architect.ai;

/** Result of one tool call: text for the model, an optional PNG for vision critique, and whether it failed. */
public record ToolOutput(String text, byte[] png, boolean error) {
    public static ToolOutput ok(String text) { return new ToolOutput(text, null, false); }
    public static ToolOutput err(String text) { return new ToolOutput(text, null, true); }
}
