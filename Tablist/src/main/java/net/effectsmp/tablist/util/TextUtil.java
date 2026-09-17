package net.effectsmp.tablist.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class TextUtil {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();

    private TextUtil() {
    }

    /** Parses a config string written with MiniMessage tags, e.g. "<light_purple>Hi". */
    public static Component miniMessage(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return MINI_MESSAGE.deserialize(input);
    }

    /** Parses a LuckPerms-style legacy prefix, e.g. "&8[&bAdmin&8] ". */
    public static Component legacy(String input) {
        if (input == null || input.isEmpty()) {
            return Component.empty();
        }
        return LEGACY_AMPERSAND.deserialize(input);
    }
}
