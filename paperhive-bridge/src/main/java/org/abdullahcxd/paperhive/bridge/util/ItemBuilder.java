package org.abdullahcxd.paperhive.bridge.util;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Builds an {@link ItemStack} in one expression, with MiniMessage names and lore (not italic unless you say so).
 * <pre>{@code
 * ItemStack wand = ItemBuilder.of(Material.BLAZE_ROD)
 *         .name("<gradient:gold:yellow>Hive Wand")
 *         .lore("<gray>Right-click to buzz", "<dark_gray>Level <level>")
 *         .glow()
 *         .tag(bukkitKey("wand"), PersistentDataType.BOOLEAN, true)   // find it again later
 *         .build();
 * }</pre>
 */
public final class ItemBuilder {

    private final ItemStack item;

    private ItemBuilder(ItemStack item) {
        this.item = item;
    }

    public static @NotNull ItemBuilder of(@NotNull Material material) {
        return new ItemBuilder(ItemStack.of(material));
    }

    /** Starts from a copy of {@code item}; the original isn't changed. */
    public static @NotNull ItemBuilder from(@NotNull ItemStack item) {
        return new ItemBuilder(item.clone());
    }

    public @NotNull ItemBuilder amount(int amount) {
        item.setAmount(amount);
        return this;
    }

    /** The display name, in MiniMessage. */
    public @NotNull ItemBuilder name(@NotNull String name) {
        return meta(meta -> meta.displayName(Text.item(name)));
    }

    /** The lore lines (replacing any), in MiniMessage. */
    public @NotNull ItemBuilder lore(@NotNull String @NotNull ... lines) {
        return meta(meta -> meta.lore(Arrays.stream(lines).map(Text::item).toList()));
    }

    public @NotNull ItemBuilder enchant(@NotNull Enchantment enchantment, int level) {
        return meta(meta -> meta.addEnchant(enchantment, level, true));
    }

    /** The enchantment shimmer without an enchantment. */
    public @NotNull ItemBuilder glow() {
        return meta(meta -> meta.setEnchantmentGlintOverride(true));
    }

    public @NotNull ItemBuilder unbreakable() {
        return meta(meta -> meta.setUnbreakable(true));
    }

    public @NotNull ItemBuilder flags(@NotNull ItemFlag @NotNull ... flags) {
        return meta(meta -> meta.addItemFlags(flags));
    }

    /** Stores a value on the item (its persistent data), to recognise it later with {@code getPersistentDataContainer()}. */
    public <P, C> @NotNull ItemBuilder tag(@NotNull NamespacedKey key, @NotNull PersistentDataType<P, C> type, @NotNull C value) {
        return meta(meta -> meta.getPersistentDataContainer().set(key, type, value));
    }

    /** Anything else, on the item's meta. */
    public @NotNull ItemBuilder meta(@NotNull Consumer<ItemMeta> edit) {
        item.editMeta(edit);
        return this;
    }

    /** A new stack each call, so one builder can hand out many. */
    public @NotNull ItemStack build() {
        return item.clone();
    }

}
