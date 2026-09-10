package com.takashi.dungeons.shop;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * What a player pays with.
 *
 * <p>The same shape as {@link com.takashi.dungeons.mob.MobProvider}, for the same reason: the core
 * plugin may not hard depend on an economy, so there is an interface, a fallback that always works
 * ({@link ItemCurrency}) and room for an optional integration. Phase 12's TakashiMarket plugs its
 * coin in here through the phase 8 API; nothing in the shop has to change when it does.
 *
 * <h2>Why the amounts are doubles</h2>
 * An item count is a whole number and {@link ItemCurrency} treats it as one. The interface is still
 * declared in {@code double} because every Vault-shaped economy is, and phase 8 promises no
 * breaking changes — widening this signature after an addon has implemented it would break exactly
 * the addons the promise is for. Prices in {@code shop.yml} are integers, so no rounding ever
 * happens on the path that exists today.
 */
public interface ShopCurrency {

    /** Registry id, for logs and command output. */
    String id();

    /**
     * Whether this currency can be used right now.
     *
     * <p>{@link ItemCurrency} is always available; an economy-backed one is not, and it answers for
     * itself instead of the shop guessing — the {@code isAvailable()} rule the mob providers set.
     */
    boolean isAvailable();

    /** What this player currently has. */
    double balance(Player player);

    /**
     * Takes the amount, and only if all of it is there.
     *
     * <p>All-or-nothing by contract: a partial withdrawal would leave a player who could not afford
     * something poorer for having tried.
     *
     * @return {@code false} when the player cannot pay — nothing was taken
     */
    boolean take(Player player, double amount);

    /** The amount as a player should read it — "12 Emeralds", "12 coins". */
    Component format(double amount);
}
