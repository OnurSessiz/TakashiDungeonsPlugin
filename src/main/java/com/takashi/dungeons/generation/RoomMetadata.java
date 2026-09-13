package com.takashi.dungeons.generation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Raw metadata read from a {@code .yml} file — the part that is independent of the schematic.
 *
 * <p>Doors are held here as {@link Vec3i}, NOT as {@link DoorAnchor}: working out which wall a
 * door is in requires the room's box, and that comes from the schematic. The join happens in
 * {@link RoomTemplateStore}.
 *
 * <p><b>Why one {@code .yml} per room rather than a single central file:</b> the map team will
 * be 2-3 people working in parallel. In one shared file everybody touches the same lines and
 * every export produces a merge conflict ({@code generation.md} §8).
 *
 * @param type      role in the graph
 * @param weight    share in the weighted random selection
 * @param doorLocal door anchors, in file order
 * @param maxPerDungeon per-size cap; a size missing from the map is uncapped
 */
public record RoomMetadata(RoomType type, int weight, List<Vec3i> doorLocal,
                           Map<DungeonSize, Integer> maxPerDungeon) {

    /** Default when there is no metadata file: normal room, standard weight, no doors, no cap. */
    public static final RoomMetadata DEFAULT =
            new RoomMetadata(RoomType.NORMAL, 100, List.of(), Map.of());

    public RoomMetadata {
        doorLocal = List.copyOf(doorLocal);
        maxPerDungeon = Map.copyOf(maxPerDungeon);
    }

    /**
     * Parses the YAML. Fails loudly with a clear message rather than falling back to a default.
     *
     * <p>Deliberately strict: a mistyped anchor shifts the room by one block, the walls
     * interpenetrate, and the mistake only becomes visible after the paste, by eye. Blowing up
     * loudly at load time tells the map team the file name and which line is at fault.
     *
     * @param section the file's root section
     * @param name    the template name to show in error messages
     */
    public static RoomMetadata parse(ConfigurationSection section, String name) {
        RoomType type = RoomType.parse(section.getString("type", "normal"), name);

        int weight = section.getInt("weight", 100);
        if (weight <= 0) {
            throw new IllegalArgumentException(name + ": weight must be positive (found: "
                    + weight + "). To disable a room, move the file out of the folder.");
        }

        List<Vec3i> doors = new ArrayList<>();
        List<?> raw = section.getList("doors");
        if (raw != null) {
            for (int i = 0; i < raw.size(); i++) {
                doors.add(parseAnchor(raw.get(i), name, i));
            }
        }

        return new RoomMetadata(type, weight, doors, parseCaps(section, name));
    }

    /**
     * Parses {@code max-per-dungeon} — how many copies of this room one dungeon may hold.
     *
     * <p>Two accepted forms:
     * <pre>
     * max-per-dungeon: 1            # the same cap for every size
     *
     * max-per-dungeon:              # per size; a size left out stays uncapped
     *   small: 0
     *   medium: 1
     *   large: 2
     * </pre>
     *
     * <p>Absent entirely means uncapped, so every {@code .yml} written before this field existed
     * keeps its old behaviour.
     *
     * <p>Strict in the same way the rest of this class is: a misspelt size key ({@code mediumm})
     * would silently leave the room uncapped, and the operator would be left staring at a
     * dungeon holding three of a room they capped at one.
     */
    private static Map<DungeonSize, Integer> parseCaps(ConfigurationSection section, String name) {
        if (!section.contains("max-per-dungeon")) {
            return Map.of();
        }
        String where = name + ": max-per-dungeon";
        Map<DungeonSize, Integer> caps = new EnumMap<>(DungeonSize.class);

        if (section.isInt("max-per-dungeon")) {
            int value = section.getInt("max-per-dungeon");
            checkCap(value, where);
            for (DungeonSize size : DungeonSize.values()) {
                caps.put(size, value);
            }
        } else {
            ConfigurationSection sub = section.getConfigurationSection("max-per-dungeon");
            if (sub == null) {
                throw new IllegalArgumentException(where + " must be a whole number or a map of "
                        + "size -> number (valid sizes: " + sizeKeys() + ")");
            }
            for (String key : sub.getKeys(false)) {
                DungeonSize size = parseSize(key);
                if (size == null) {
                    throw new IllegalArgumentException(where + ": unknown size '" + key
                            + "' (valid sizes: " + sizeKeys() + ")");
                }
                if (!sub.isInt(key)) {
                    throw new IllegalArgumentException(where + "." + key
                            + " must be a whole number (found: " + sub.get(key) + ")");
                }
                int value = sub.getInt(key);
                checkCap(value, where + "." + key);
                caps.put(size, value);
            }
        }

        // Every size capped at zero means the room can never be placed anywhere. That is a
        // mistake dressed as a setting, and it is silent: the room loads, appears in
        // /tdungeons rooms, and simply never shows up in a dungeon. Same reasoning - and the
        // same fix - as a non-positive weight.
        boolean allZero = caps.size() == DungeonSize.values().length
                && caps.values().stream().allMatch(v -> v == 0);
        if (allZero) {
            throw new IllegalArgumentException(where + " is 0 for every size, so this room could "
                    + "never be placed. To disable a room, move the file out of the folder.");
        }
        return caps;
    }

    private static void checkCap(int value, String where) {
        if (value < 0) {
            throw new IllegalArgumentException(where + " cannot be negative (found: " + value + ")");
        }
    }

    private static DungeonSize parseSize(String key) {
        String wanted = key.trim().toLowerCase(Locale.ROOT);
        for (DungeonSize size : DungeonSize.values()) {
            if (size.key().equals(wanted)) {
                return size;
            }
        }
        return null;
    }

    private static String sizeKeys() {
        List<String> keys = new ArrayList<>();
        for (DungeonSize size : DungeonSize.values()) {
            keys.add(size.key());
        }
        return String.join(", ", keys);
    }

    /** Parses a single {@code [x, y, z]} entry. */
    private static Vec3i parseAnchor(Object entry, String name, int index) {
        String where = name + ": doors[" + index + "]";
        if (!(entry instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " must be a list — expected form: [x, y, z]");
        }
        if (list.size() != 3) {
            throw new IllegalArgumentException(where + " must contain exactly 3 numbers [x, y, z] "
                    + "(found: " + list.size() + " entries)");
        }
        return new Vec3i(
                intAt(list.get(0), where, "x"),
                intAt(list.get(1), where, "y"),
                intAt(list.get(2), where, "z"));
    }

    private static int intAt(Object value, String where, String axis) {
        if (value instanceof Number number) {
            // A coordinate written with decimals (8.5) would shift the room half a block if
            // silently rounded; decimals are meaningless in block coordinates, so they are
            // rejected.
            if (number.doubleValue() != number.intValue()) {
                throw new IllegalArgumentException(where + " " + axis
                        + " must be a whole number (found: " + number + ")");
            }
            return number.intValue();
        }
        throw new IllegalArgumentException(where + " " + axis + " must be a number (found: " + value + ")");
    }
}
