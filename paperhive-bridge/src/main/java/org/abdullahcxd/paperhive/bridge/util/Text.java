package org.abdullahcxd.paperhive.bridge.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.NotNull;

/**
 * MiniMessage in one call: {@code Text.mini("<gold>Hello <name>", Placeholder.unparsed("name", player.getName()))}.
 * Use {@code Placeholder.unparsed} for anything a player typed, so they can't inject tags.
 */
public final class Text {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Text() {}

    public static @NotNull Component mini(@NotNull String text, @NotNull TagResolver @NotNull ... placeholders) {
        return MINI.deserialize(text, placeholders);
    }

    /** {@link #mini}, without the italics Minecraft puts on item names and lore unless told otherwise. */
    public static @NotNull Component item(@NotNull String text, @NotNull TagResolver @NotNull ... placeholders) {
        return mini(text, placeholders).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Plain text with the tags removed, e.g. for logs. */
    public static @NotNull String strip(@NotNull String text) {
        return MINI.stripTags(text);
    }

}
