package com.takashi.dungeons.mob;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * A source of mobs. The plugin <b>never creates a mob type</b> — it asks a provider to produce an
 * entity that already exists somewhere: vanilla's registry, MythicMobs' configuration, or a
 * future third one.
 *
 * <h2>Why this is an interface and not an if-chain</h2>
 * {@code anahedef.md} §4: adding a new mob source must be "implement {@code MobProvider}", not
 * "edit the spawner". The spawner in phase 3B knows nothing about MythicMobs; it holds a
 * {@link MobDefinition} and asks whoever owns that definition's provider id to spawn it.
 *
 * <p><b>This interface is public API surface.</b> Phase 8 exposes it so addons can register their
 * own source, and {@code anahedef.md} §5 forbids breaking changes to it. New capabilities arrive
 * as {@code default} methods.
 *
 * <h2>Availability is asked, never assumed</h2>
 * {@link #isAvailable()} is separate from existence: {@code MythicMobsProvider} is always
 * constructed and reports {@code false} when the plugin is absent. Registering the provider only
 * when its plugin is present would push the same question into every call site, and the
 * <i>reason</i> a definition is disabled would be lost — the registry could only say "unknown
 * provider" where it should say "MythicMobs kurulu değil".
 */
public interface MobProvider {

    /** The prefix used in {@code mobs.yml} addressing — {@code vanilla:ZOMBIE}. Lower case. */
    String id();

    /** Human-readable name for command and log output. */
    String displayName();

    /** Whether this provider can spawn anything right now. */
    boolean isAvailable();

    /**
     * Whether this provider recognises the key.
     *
     * <p>Checked at load time so a typo is reported when {@code mobs.yml} is read, not the first
     * time a player walks into the room that would have contained the mob.
     */
    boolean supports(String mobKey);

    /**
     * Spawns the mob at the location, on the main thread.
     *
     * @return the spawned entity, or {@code null} if the provider refused — a key it does not
     *         know, or a plugin that went away between the load and now. Never throws for a
     *         missing mob; a dungeon with one absent mob type must still be enterable.
     */
    @Nullable LivingEntity spawn(String mobKey, Location location);

    /** Keys this provider knows, for tab-complete and {@code /tdungeons mob providers}. */
    Collection<String> knownKeys();

    /**
     * Whether definitions from this provider have their stats overridden unless told otherwise.
     *
     * <p>{@code true} for vanilla — a vanilla zombie has no stat design to protect. {@code false}
     * for external sources: a MythicMobs mob arrives with the stats its author wrote, and
     * overwriting them by default would delete the reason that plugin is installed
     * ({@code anahedef.md} §4). An explicit {@code statOverride} in {@code mobs.yml} always wins.
     */
    default boolean defaultStatOverride() {
        return true;
    }
}
