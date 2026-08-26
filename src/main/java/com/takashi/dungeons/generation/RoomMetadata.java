package com.takashi.dungeons.generation;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

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
 */
public record RoomMetadata(RoomType type, int weight, List<Vec3i> doorLocal) {

    /** Default when there is no metadata file: normal room, standard weight, no doors. */
    public static final RoomMetadata DEFAULT = new RoomMetadata(RoomType.NORMAL, 100, List.of());

    public RoomMetadata {
        doorLocal = List.copyOf(doorLocal);
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

        return new RoomMetadata(type, weight, doors);
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
