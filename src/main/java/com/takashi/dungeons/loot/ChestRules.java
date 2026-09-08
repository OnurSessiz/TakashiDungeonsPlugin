package com.takashi.dungeons.loot;

import com.takashi.dungeons.generation.RoomType;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where chests come from and which table each one draws — the {@code chests:} block of
 * {@code loot.yml}.
 *
 * <h2>Hybrid, and the mapper wins</h2>
 * A schematic that contains chests has those chests filled, wherever the mapper put them. Only a
 * room with none gets one placed procedurally. The reason is the same one that keeps spawn points
 * out of the room {@code .yml} files: a person who built the room knows where a chest belongs in
 * it, and a search that overrules them is a search that puts treasure behind the pillar they
 * carved to hide it.
 *
 * <h2>Every chest the mapper placed is filled, not just the first</h2>
 * Two chests in a room means two chests. Splitting one table roll between them would make both
 * disappointing, and ignoring the second is the silent substitution this package refuses
 * everywhere else. A double chest is the exception and it is not one really: a player opens it
 * once and sees one inventory, so it counts as one chest ({@code LootPopulator} groups the halves).
 *
 * <h2>The boss room</h2>
 * It has a table, so a chest a mapper put there <b>is</b> filled — hidden loot in a boss lair is
 * not strange. But it is in {@link #proceduralSkip}, so nothing is ever <i>placed</i> there: the
 * room's reward is the chest that appears where the boss died (phase 4C), and a second chest
 * standing in the corner from the moment the door opens would tell the player the ending before
 * the fight does.
 *
 * <h2>A room type with no table is not touched at all</h2>
 * The entrance ships that way. Not "an empty chest" — untouched: if a mapper drew a chest into
 * their entrance hall, they get exactly the chest they drew, and the plugin neither stocks it nor
 * adds another. Players are teleported into that room and gather there; a free reward before the
 * first corridor is a different game.
 *
 * @param tables          room type to loot table id; a type absent from here is left alone
 * @param containers      the blocks in a schematic that count as a chest the mapper placed
 * @param placeMaterial   what a procedurally placed chest is made of
 * @param placeWhenMissing whether a room with no chest of its own gets one
 * @param proceduralSkip  room types that never get a chest placed, even with no chest of their own
 */
public record ChestRules(Map<RoomType, String> tables, Set<Material> containers,
                         Material placeMaterial, boolean placeWhenMissing,
                         Set<RoomType> proceduralSkip) {

    /**
     * What ships, and what an absent {@code chests:} block means.
     *
     * <p>Out-of-box has to work: a server that never opens {@code loot.yml} still gets chests, and
     * they still avoid the entrance and the boss room.
     */
    public static final ChestRules DEFAULT = new ChestRules(
            Map.of(RoomType.NORMAL, "room_chest", RoomType.BOSS, "room_chest"),
            EnumSet.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL),
            Material.CHEST, true, EnumSet.of(RoomType.BOSS));

    public ChestRules {
        tables = Map.copyOf(tables);
        containers = Set.copyOf(containers);
        proceduralSkip = Set.copyOf(proceduralSkip);
        placeMaterial = placeMaterial == null ? Material.CHEST : placeMaterial;
    }

    /** The table this room type draws from, or {@code null} when the plugin leaves it alone. */
    public @Nullable String tableFor(RoomType type) {
        String id = tables.get(type);
        return id == null || id.isBlank() ? null : id;
    }

    /** Whether a room of this type may have a chest placed in it when it has none. */
    public boolean mayPlaceIn(RoomType type) {
        return placeWhenMissing && !proceduralSkip.contains(type) && tableFor(type) != null;
    }

    public boolean isContainer(Material material) {
        return containers.contains(material);
    }

    /**
     * Reads the {@code chests:} block. An absent block means {@link #DEFAULT}; a broken field falls
     * back to its default value with a line on the console rather than taking the file down.
     *
     * @param problems collects what was wrong, for the caller to log — this class does no logging
     *                 of its own so it can be read without a server
     */
    public static ChestRules parse(@Nullable ConfigurationSection section, List<String> problems) {
        if (section == null) {
            return DEFAULT;
        }
        return new ChestRules(
                readTables(section.getConfigurationSection("tables"), problems),
                readContainers(section.getStringList("containers"), problems),
                readMaterial(section.getString("place-material"), "place-material",
                        DEFAULT.placeMaterial(), problems),
                section.getBoolean("place-when-missing", DEFAULT.placeWhenMissing()),
                readTypes(section.getStringList("procedural-skip"), "procedural-skip",
                        DEFAULT.proceduralSkip(), problems));
    }

    /**
     * Reads {@code tables:} — one loot table id per room type.
     *
     * <p>An absent block means the shipped mapping rather than "no chests anywhere". An operator
     * who wants no chests writes the types out with blank values; one who simply has not thought
     * about it gets a working dungeon.
     */
    private static Map<RoomType, String> readTables(@Nullable ConfigurationSection section,
                                                    List<String> problems) {
        if (section == null) {
            return DEFAULT.tables();
        }
        Map<RoomType, String> tables = new EnumMap<>(RoomType.class);
        for (String key : section.getKeys(false)) {
            RoomType type = parseType(key);
            if (type == null) {
                problems.add("chests.tables: unknown room type '" + key + "' - valid: "
                        + typeList());
                continue;
            }
            String table = section.getString(key, "");
            // A blank value is meaningful and stays out of the map: it is how an operator says
            // "leave this room type alone", which is what the shipped entrance entry does.
            if (table != null && !table.isBlank()) {
                tables.put(type, table.trim());
            }
        }
        return tables;
    }

    private static Set<Material> readContainers(List<String> raw, List<String> problems) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT.containers();
        }
        Set<Material> found = EnumSet.noneOf(Material.class);
        for (String name : raw) {
            Material material = readMaterial(name, "containers", null, problems);
            if (material != null) {
                found.add(material);
            }
        }
        // Every entry was a typo. Falling back beats running with an empty set, which would look
        // exactly like "the mapper's chests are being ignored" and say nothing about why.
        return found.isEmpty() ? DEFAULT.containers() : found;
    }

    private static Set<RoomType> readTypes(List<String> raw, String where, Set<RoomType> fallback,
                                           List<String> problems) {
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        Set<RoomType> found = EnumSet.noneOf(RoomType.class);
        for (String name : raw) {
            RoomType type = parseType(name);
            if (type == null) {
                problems.add("chests." + where + ": unknown room type '" + name + "' - valid: "
                        + typeList());
                continue;
            }
            found.add(type);
        }
        return found;
    }

    private static @Nullable Material readMaterial(@Nullable String raw, String where,
                                                   @Nullable Material fallback,
                                                   List<String> problems) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw.trim());
        if (material == null || !material.isBlock()) {
            problems.add("chests." + where + ": '" + raw + "' is not a block material"
                    + (fallback == null ? " - ignored" : " - using " + fallback + " instead"));
            return fallback;
        }
        return material;
    }

    private static @Nullable RoomType parseType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        for (RoomType type : RoomType.values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        return null;
    }

    private static String typeList() {
        List<String> names = new ArrayList<>(RoomType.values().length);
        for (RoomType type : RoomType.values()) {
            names.add(type.yamlValue());
        }
        return String.join(", ", names);
    }
}
