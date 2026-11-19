package org.abdullahcxd.paperhive.plugin.command;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Colours for console output (Paperhive's own commands use them, Hive plugin commands may too):
 * {@code sender.sendMessage(ConsoleText.success("Saved " + ConsoleText.name(world)))}. They're ANSI escapes; Paperhive
 * removes them wherever there's no colour terminal to show them (a service's log, the control socket, log files).
 */
public final class ConsoleText {

    private static final String RESET = "\u001B[0m";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

    private static final String HONEY = "38;5;214";
    private static final String GREEN = "38;5;114";
    private static final String RED = "38;5;203";
    private static final String YELLOW = "38;5;221";
    private static final String CYAN = "38;5;117";
    private static final String GRAY = "38;5;245";

    private ConsoleText() {}

    /** Something worked: a green {@code »} before the text. */
    public static @NotNull String success(@NotNull String text) {
        return paint(GREEN, "» ") + text;
    }

    /** Something failed or can't be done: a red {@code ×} before the text. */
    public static @NotNull String error(@NotNull String text) {
        return paint(RED, "× ") + text;
    }

    /** Worth noticing, not an error: a yellow {@code !} before the text. */
    public static @NotNull String warning(@NotNull String text) {
        return paint(YELLOW, "! ") + text;
    }

    /** Something happening now ("Starting lobby"): a honey {@code »} before the text. */
    public static @NotNull String info(@NotNull String text) {
        return paint(HONEY, "» ") + text;
    }

    /** A server's or plugin's name. */
    public static @NotNull String name(@NotNull String text) {
        return paint("1;" + HONEY, text);
    }

    /** A value: a port, a path, a setting. */
    public static @NotNull String value(@NotNull String text) {
        return paint("1", text);
    }

    /** Something to type: {@code start lobby}. */
    public static @NotNull String command(@NotNull String text) {
        return paint(CYAN, text);
    }

    /** Less important text: hints, units, headers. */
    public static @NotNull String muted(@NotNull String text) {
        return paint(GRAY, text);
    }

    /** A server state by name, coloured by what it means: RUNNING green, CRASHED red, on the way yellow, else gray. */
    public static @NotNull String state(@NotNull String state) {
        String color = switch (state.toUpperCase(Locale.ROOT)) {
            case "RUNNING" -> GREEN;
            case "CRASHED" -> RED;
            case "STARTING", "STOPPING" -> YELLOW;
            default -> GRAY;
        };
        return paint(color, state);
    }

    /** Ticks per second, one decimal, capped at 20 like Paper's {@code /tps}: green at full speed, yellow below 18, red below 15. */
    public static @NotNull String tps(double tps) {
        double shown = Math.min(tps, 20);
        return paint(shown >= 18 ? GREEN : shown >= 15 ? YELLOW : RED, String.format(Locale.ROOT, "%.1f", shown));
    }

    /** {@code text} padded with spaces to {@code width} visible characters (colours take no room). */
    public static @NotNull String pad(@NotNull String text, int width) {
        int visible = strip(text).length();
        return visible >= width ? text : text + " ".repeat(width - visible);
    }

    /** {@code text} without colours. */
    public static @NotNull String strip(@NotNull String text) {
        return text.indexOf('\u001B') < 0 ? text : ANSI.matcher(text).replaceAll("");
    }

    private static String paint(String code, String text) {
        return "\u001B[" + code + "m" + text + RESET;
    }

}
