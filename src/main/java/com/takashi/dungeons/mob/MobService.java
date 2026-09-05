package com.takashi.dungeons.mob;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.random.RandomGenerator;

/**
 * Turns a {@link MobDefinition} into a mob standing in the world.
 *
 * <h2>The order matters</h2>
 * Provider spawns → stats → equipment → name. Health is written <b>last among the stats</b> and
 * the entity is healed to full afterwards: raising max health does not raise current health, so a
 * mob given 80 max health would walk out of the spawner on 20 and die to three hits. This is the
 * single most common way a stat system looks broken.
 *
 * <h2>statOverride is the whole contract</h2>
 * When it is off, this class touches nothing but the name — no attributes, no armour. That is not
 * a limitation to work around; it is the promise made to every mob plugin the server has
 * installed ({@code anahedef.md} §4). Difficulty scaling rides on the same flag, because a
 * multiplier applied to somebody else's carefully written boss is still an override.
 */
public final class MobService {

    private final TakashiDungeonsPlugin plugin;
    private final MobRegistry registry;

    public MobService(TakashiDungeonsPlugin plugin, MobRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    public MobRegistry registry() {
        return registry;
    }

    /**
     * Spawns a mob at the location.
     *
     * <p>Main thread only — entity creation is not thread safe and Bukkit will throw. The check is
     * here rather than in the caller because phase 3B populates rooms from a paste chain that runs
     * on FAWE's thread, and the mistake is easy to make once and hard to see afterwards.
     *
     * @return the spawned entity, or {@code null} if the provider refused
     */
    public @Nullable LivingEntity spawn(MobDefinition definition, Location location,
                                        Difficulty difficulty, RandomGenerator random) {
        if (!plugin.getServer().isPrimaryThread()) {
            throw new IllegalStateException("Mob spawn'ı main thread'de yapılmalı: " + definition.id());
        }
        MobProvider provider = registry.provider(definition.providerId());
        if (provider == null || !provider.isAvailable()) {
            return null;
        }
        LivingEntity entity = provider.spawn(definition.mobKey(), location);
        if (entity == null) {
            return null;
        }

        if (definition.baby() && entity instanceof Ageable ageable) {
            ageable.setBaby();
        }

        if (definition.resolveStatOverride(provider)) {
            DifficultyScaling scaling = registry.scaling(difficulty);
            applyStats(entity, definition, scaling, random);
            applyArmor(entity, scaling, random);
        }
        applyName(entity, definition);
        applyDungeonBehaviour(entity);
        return entity;
    }

    /** Convenience for the command layer: spawn by registry id at the default difficulty. */
    public @Nullable LivingEntity spawn(String id, Location location, RandomGenerator random) {
        MobDefinition definition = registry.definition(id);
        return definition == null
                ? null : spawn(definition, location, registry.defaultDifficulty(), random);
    }

    private void applyStats(LivingEntity entity, MobDefinition definition,
                            DifficultyScaling scaling, RandomGenerator random) {
        if (definition.damage() != null) {
            set(entity, Attribute.ATTACK_DAMAGE,
                    definition.damage().roll(random) * scaling.damage());
        }
        if (definition.speed() != null) {
            set(entity, Attribute.MOVEMENT_SPEED,
                    definition.speed().roll(random) * scaling.speed());
        }
        if (definition.health() != null) {
            double max = definition.health().roll(random) * scaling.health();
            if (set(entity, Attribute.MAX_HEALTH, max)) {
                // Max health and current health are separate values. Without this line every mob
                // arrives at whatever health its species starts with, and a 200-hp boss dies like
                // a zombie.
                entity.setHealth(Math.min(max, entity.getAttribute(Attribute.MAX_HEALTH).getValue()));
            }
        }
    }

    /**
     * Writes one attribute.
     *
     * @return {@code false} when the entity has no such attribute — a slime has no attack damage
     *         attribute, and refusing quietly is correct: the definition is not wrong, the
     *         attribute simply does not exist on that species
     */
    private boolean set(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.setBaseValue(value);
        return true;
    }

    private void applyArmor(LivingEntity entity, DifficultyScaling scaling, RandomGenerator random) {
        EntityEquipment equipment = entity.getEquipment();
        if (equipment == null || scaling.armor().isNone()
                || random.nextDouble() >= scaling.armorChance()) {
            return;
        }
        ArmorTier tier = scaling.armor();
        wear(equipment, tier.helmet(), equipment::setHelmet, equipment::setHelmetDropChance);
        wear(equipment, tier.chestplate(), equipment::setChestplate,
                equipment::setChestplateDropChance);
        wear(equipment, tier.leggings(), equipment::setLeggings, equipment::setLeggingsDropChance);
        wear(equipment, tier.boots(), equipment::setBoots, equipment::setBootsDropChance);
    }

    /**
     * Puts one piece on, with a zero drop chance.
     *
     * <p>The drop chance is the point: loot is phase 4's system and comes from a table the
     * operator writes. Armour that falls off a mob would be a second, invisible loot source that
     * nobody configured and that scales with difficulty — exactly the kind of leak that makes a
     * hard dungeon the most profitable place to farm iron.
     */
    private void wear(EntityEquipment equipment, @Nullable Material material,
                      java.util.function.Consumer<ItemStack> slot,
                      java.util.function.Consumer<Float> dropChance) {
        if (material == null) {
            return;
        }
        slot.accept(new ItemStack(material));
        dropChance.accept(0f);
    }

    private void applyName(LivingEntity entity, MobDefinition definition) {
        String name = definition.displayName();
        if (name == null || name.isBlank()) {
            return;
        }
        entity.customName(MiniMessage.miniMessage().deserialize(name));
        entity.setCustomNameVisible(true);
    }

    /**
     * The two behaviours every dungeon mob needs, whatever its provider or stats.
     *
     * <p><b>No despawn:</b> a dungeon is a place the party walks away from and comes back to. A
     * room that empties itself while nobody is looking reads as the generator having failed, and
     * the instance's own teardown is what removes these mobs — they cannot outlive it.
     *
     * <p><b>No item pickup:</b> phase 4 drops loot on the floor. A mob that picks it up and wears
     * it has quietly deleted the player's reward.
     */
    private void applyDungeonBehaviour(LivingEntity entity) {
        entity.setRemoveWhenFarAway(false);
        entity.setCanPickupItems(false);
    }
}
