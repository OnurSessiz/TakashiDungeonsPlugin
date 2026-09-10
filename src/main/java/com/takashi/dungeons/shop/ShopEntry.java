package com.takashi.dungeons.shop;

import com.takashi.dungeons.loot.LootItem;
import com.takashi.dungeons.loot.LootRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * One line of stock: what is for sale, what it costs, and how often one player may buy it.
 *
 * <h2>The item is defined HERE, not in loot.yml</h2>
 * A shop entry carries its own item block, read by {@link LootItem#parse} so that material, name,
 * lore, enchantments and flags all behave exactly as they do in a chest. What it must <b>not</b> do
 * is live in {@code loot.yml}: every entry in that file belongs to a rarity pool, so a shop-only
 * item added there would start turning up in chests. It is the same trap as putting the merchant in
 * {@code mobs.yml}, where every entry belongs to a class pool and would be spawned as an enemy.
 *
 * <p>{@code loot: <id>} is the way back the other direction — selling something the catalogue
 * already defines, without copying it.
 *
 * <h2>The amount is fixed</h2>
 * {@link LootItem} carries a range because a chest draw is a roll. A price that buys "three to
 * seven bread" is a slot machine, so a range here is refused with that as the reason.
 *
 * @param id     key in {@code shop.yml} → {@code stock}, and the slot order comes from the file
 * @param item   the goods; its {@code amount} is fixed by construction
 * @param price  what one purchase costs, in whatever {@link ShopCurrency} is configured
 * @param limit  how many times one player may buy this in one dungeon; {@code 0} = no limit
 */
public record ShopEntry(String id, LootItem item, int price, int limit) {

    /** How many of the item one purchase hands over. */
    public int amount() {
        return item.amount().min();
    }

    public boolean hasLimit() {
        return limit > 0;
    }

    /**
     * Reads one {@code stock.<id>} block.
     *
     * <p>Strict, like every other catalogue in this plugin: a bad entry is disabled with a reason
     * naming the file, the entry and the field. A shop that silently sells the wrong thing is a
     * balance complaint nobody traces back to a typo.
     */
    public static ShopEntry parse(ConfigurationSection section, String id,
                                  @Nullable LootRegistry loot) {
        String where = "stock." + id;

        int price = section.getInt("price", -1);
        if (price < 1) {
            throw new IllegalArgumentException(where + ": 'price' is required and must be at least "
                    + "1 (found: " + (section.isSet("price") ? String.valueOf(price) : "nothing")
                    + "). To hand something out for free, do not put it in a shop.");
        }

        int limit = section.getInt("limit", 0);
        if (limit < 0) {
            throw new IllegalArgumentException(where + ": 'limit' cannot be negative - write 0, or "
                    + "leave it out, for no limit.");
        }

        LootItem item = readItem(section, id, where, loot);
        if (!item.amount().isFixed()) {
            throw new IllegalArgumentException(where + ": 'amount' must be a single number here, "
                    + "not the range " + item.amount() + " - a fixed price that buys a random "
                    + "amount is a slot machine, not a shop.");
        }
        return new ShopEntry(id, item, price, limit);
    }

    /**
     * The goods: either a reference into the loot catalogue, or an item defined right here.
     *
     * <p>A {@code loot:} id that names nothing is refused rather than falling through to the inline
     * form — the operator meant a catalogue item, and a shop quietly selling a different thing is
     * worse than a shop with one entry switched off.
     */
    private static LootItem readItem(ConfigurationSection section, String id, String where,
                                     @Nullable LootRegistry loot) {
        String reference = section.getString("loot");
        if (reference == null || reference.isBlank()) {
            return LootItem.parse(section, id, where);
        }
        LootItem catalogued = loot == null ? null : loot.item(reference.trim());
        if (catalogued == null) {
            throw new IllegalArgumentException(where + ": loot '" + reference
                    + "' is not an item in loot.yml - check the id, or define the item here "
                    + "with 'material:' instead.");
        }
        // The amount is the shop's business, not the catalogue's: a chest rolls 1-3 diamonds,
        // a shop sells exactly what the price says.
        int amount = section.getInt("amount", catalogued.amount().min());
        return new LootItem(catalogued.id(), catalogued.itemClass(), catalogued.weight(),
                catalogued.material(), com.takashi.dungeons.loot.CountRange.fixed(amount),
                catalogued.displayName(), catalogued.lore(), catalogued.enchantments(),
                catalogued.unbreakable(), catalogued.customModelData(), catalogued.glow(),
                catalogued.flags());
    }

    @Override
    public String toString() {
        return id + " (" + amount() + "x " + item.material() + ", " + price
                + (hasLimit() ? ", limit " + limit : "") + ")";
    }
}
