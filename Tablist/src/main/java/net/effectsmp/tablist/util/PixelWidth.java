package net.effectsmp.tablist.util;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Approximate pixel-width measurements for Minecraft's built-in ("default")
 * font, used to line the ping up in a fixed column regardless of how long a
 * player's name or LuckPerms prefix is.
 * <p>
 * These numbers come from the widely-used community-sourced width tables
 * for the vanilla font (most glyphs are 6px wide including their 1px of
 * trailing spacing; a handful of narrow characters like "i"/"l"/"." are
 * less). They're a best-effort approximation, not something pulled from a
 * live client, so the column position config value
 * (ping.align-column) is there to be nudged a few pixels either way if it
 * doesn't line up perfectly on your screen.
 */
public final class PixelWidth {

    private static final int DEFAULT_WIDTH = 6;
    private static final Map<Character, Integer> WIDTHS = new HashMap<>();

    // Strips legacy '&'/section-sign color and format codes (including the
    // 6-code hex sequence LuckPerms/MiniMessage-legacy uses for "&#RRGGBB"
    // style colors) so only the visible characters are measured.
    private static final Pattern HEX_CODE = Pattern.compile("(?i)[&§]#[0-9a-f]{6}");
    private static final Pattern LEGACY_CODE = Pattern.compile("(?i)[&§][0-9a-fk-orx]");

    static {
        WIDTHS.put(' ', 4);
        for (char c : new char[]{'!', '.', ',', ':', ';', '|', '\''}) {
            WIDTHS.put(c, 2);
        }
        WIDTHS.put('`', 3);
        WIDTHS.put('i', 2);
        WIDTHS.put('l', 3);
        WIDTHS.put('t', 4);
        WIDTHS.put('f', 5);
        WIDTHS.put('k', 5);
        WIDTHS.put('I', 4);
        for (char c : new char[]{'(', ')', '[', ']', '{', '}', '<', '>', '"'}) {
            WIDTHS.put(c, 5);
        }
        WIDTHS.put('@', 7);
        WIDTHS.put('~', 7);
    }

    private PixelWidth() {
    }

    /** Strips '&'/section-sign color and formatting codes, leaving just the
     * characters that are actually drawn. */
    public static String stripCodes(String text) {
        if (text == null) {
            return "";
        }
        String stripped = HEX_CODE.matcher(text).replaceAll("");
        return LEGACY_CODE.matcher(stripped).replaceAll("");
    }

    /**
     * @param plainText text with no color/format codes in it (see
     *                  {@link #stripCodes(String)})
     * @param bold      whether this text is rendered bold - bold adds 1px
     *                  per character in Minecraft's font renderer
     */
    public static int widthOf(String plainText, boolean bold) {
        if (plainText == null || plainText.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < plainText.length(); i++) {
            total += WIDTHS.getOrDefault(plainText.charAt(i), DEFAULT_WIDTH);
            if (bold) {
                total += 1;
            }
        }
        return total;
    }
}
