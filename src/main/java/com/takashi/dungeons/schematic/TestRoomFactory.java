package com.takashi.dungeons.schematic;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import com.takashi.dungeons.generation.RoomType;
import com.takashi.dungeons.generation.Vec3i;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds simple test rooms in code and writes them to disk as {@code .schem} + {@code .yml}.
 *
 * <p><b>Why it exists:</b> so phase 1's generation logic can be tested before the real maps
 * (phase 10) are ready. The rooms it produces are temporary placeholders — once the map team
 * draws the real ones, they are replaced under the same file names.
 *
 * <p><b>This class writes the metadata too.</b> Since it is the only place that computes a door
 * anchor, writing the {@code .yml} by hand would be a needless source of error: if the schematic
 * changes and the metadata does not, the doors stop lining up. Both come out of one function.
 *
 * <p><b>The origin is centred:</b> the clipboard origin is set to the room's horizontal centre
 * at floor level. Rotation therefore turns the room on its own axis and the paste target is
 * directly a world point. With a corner origin, every rotation would need its own offset
 * calculation.
 *
 * <p><b>Even side lengths are now allowed</b> ({@code generation.md} §9). With anchor-based
 * placement a room being asymmetric about its origin is not a problem; {@code test_even} exists
 * precisely to verify that.
 */
public final class TestRoomFactory {

    /** One wall of a room. */
    public enum Door {
        NORTH, EAST, SOUTH, WEST;

        /** Whether the wall runs along X (north/south walls) or along Z (east/west). */
        boolean runsAlongX() {
            return this == NORTH || this == SOUTH;
        }
    }

    /**
     * A door: which wall it is in, and how many blocks it is offset from the wall's centre.
     *
     * <p>{@code offset} runs along +X on north/south walls and along +Z on east/west walls.
     * Non-zero offsets are {@code generation.md} §6.4's "break up the straight run" mechanism —
     * so that rooms don't line up as if drawn with a ruler.
     */
    public record DoorSpec(Door wall, int offset) {
        public static DoorSpec of(Door wall) {
            return new DoorSpec(wall, 0);
        }
    }

    /** Width of the door opening, in blocks — odd, so that it has a centre block. */
    private static final int DOOR_WIDTH = 3;
    /** Height of the door opening, in blocks, measured up from the floor. */
    private static final int DOOR_HEIGHT = 3;
    /** The anchor's Y: the base block of the opening, one above the room floor. */
    private static final int DOOR_BASE_Y = 1;

    private TestRoomFactory() {
    }

    /** A built room: its clipboard plus its metadata. They come from one place and don't split. */
    public record BuiltRoom(Clipboard clipboard, RoomType type, int weight, List<Vec3i> doorAnchors) {
    }

    /**
     * Builds a room that is hollow inside, closed on every wall, with the given doors cut open.
     *
     * @param sizeX  east-west width
     * @param sizeZ  north-south length
     * @param height total height (floor + interior + ceiling)
     * @param doors  the doors to cut
     */
    public static BuiltRoom buildRoom(int sizeX, int sizeZ, int height,
                                      RoomType type, int weight, List<DoorSpec> doors) {
        if (sizeX < 5 || sizeZ < 5 || height < 5) {
            throw new IllegalArgumentException(
                    "A room must be at least 5x5x5: " + sizeX + "x" + height + "x" + sizeZ);
        }

        CuboidRegion region = new CuboidRegion(
                BlockVector3.ZERO, BlockVector3.at(sizeX - 1, height - 1, sizeZ - 1));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);

        // The reference point for rotation and paste: horizontal centre, floor level.
        // With an even side there is no true centre block; the origin shifts to one side and the
        // room becomes asymmetric about it. Anchor-based placement tolerates that (§9).
        BlockVector3 origin = BlockVector3.at(sizeX / 2, 0, sizeZ / 2);
        clipboard.setOrigin(origin);

        BlockState wall = state(BlockTypes.STONE_BRICKS, "stone_bricks");
        BlockState floor = state(BlockTypes.POLISHED_ANDESITE, "polished_andesite");
        BlockState light = state(BlockTypes.GLOWSTONE, "glowstone");
        BlockState air = state(BlockTypes.AIR, "air");

        List<Vec3i> anchors = new ArrayList<>(doors.size());
        try {
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int y = 0; y < height; y++) {
                        boolean shell = y == 0 || y == height - 1
                                || x == 0 || x == sizeX - 1
                                || z == 0 || z == sizeZ - 1;
                        if (!shell) {
                            continue; // the interior volume stays empty
                        }
                        clipboard.setBlock(BlockVector3.at(x, y, z), y == 0 ? floor : wall);
                    }
                }
            }

            // light in the middle of the ceiling: the room lights itself (no sun in the
            // dungeon world)
            clipboard.setBlock(BlockVector3.at(sizeX / 2, height - 1, sizeZ / 2), light);

            for (DoorSpec door : doors) {
                anchors.add(carveDoor(clipboard, sizeX, sizeZ, origin, door, air));
            }
        } catch (WorldEditException e) {
            throw new IllegalStateException("Could not build the test room: " + e.getMessage(), e);
        }

        return new BuiltRoom(clipboard, type, weight, anchors);
    }

    /**
     * Cuts a {@value #DOOR_WIDTH}x{@value #DOOR_HEIGHT} opening in a wall.
     *
     * @return the opening's base-centre block <b>relative to the origin</b> — the door anchor
     */
    private static Vec3i carveDoor(BlockArrayClipboard clipboard, int sizeX, int sizeZ,
                                   BlockVector3 origin, DoorSpec spec, BlockState air)
            throws WorldEditException {
        Door wall = spec.wall();
        int along = wall.runsAlongX() ? sizeX : sizeZ;
        int center = along / 2 + spec.offset();
        int half = DOOR_WIDTH / 2;

        // If the opening spills into a corner the wall breaks structurally and the anchor
        // falls outside it.
        if (center - half < 1 || center + half > along - 2) {
            throw new IllegalArgumentException("The " + wall + " door at offset " + spec.offset()
                    + " spills past the wall (wall length " + along + ")");
        }

        for (int d = -half; d <= half; d++) {
            for (int y = DOOR_BASE_Y; y < DOOR_BASE_Y + DOOR_HEIGHT; y++) {
                clipboard.setBlock(doorBlock(wall, sizeX, sizeZ, center + d, y), air);
            }
        }

        BlockVector3 anchor = doorBlock(wall, sizeX, sizeZ, center, DOOR_BASE_Y);
        BlockVector3 local = anchor.subtract(origin);
        return new Vec3i(local.x(), local.y(), local.z());
    }

    /** The clipboard coordinate of a point on a wall. */
    private static BlockVector3 doorBlock(Door wall, int sizeX, int sizeZ, int along, int y) {
        return switch (wall) {
            case NORTH -> BlockVector3.at(along, y, 0);
            case SOUTH -> BlockVector3.at(along, y, sizeZ - 1);
            case WEST -> BlockVector3.at(0, y, along);
            case EAST -> BlockVector3.at(sizeX - 1, y, along);
        };
    }

    /**
     * Aliases for the Sponge {@code .schem} format — they change between versions, so they are
     * tried in order.
     *
     * <p>{@code findByFile} is deliberately not used: that method OPENS the file to detect its
     * format, and throws {@code NoSuchFileException} for a file that does not exist yet. On the
     * write path an alias is mandatory.
     */
    private static final List<String> SCHEM_ALIASES = List.of("sponge.3", "schem", "sponge", "sponge.2");

    /** Writes the clipboard to disk as {@code .schem} (Sponge), overwriting any file of that name. */
    public static File write(Clipboard clipboard, File directory, String name) throws IOException {
        File file = new File(directory, name + ".schem");
        ClipboardFormat format = null;
        for (String alias : SCHEM_ALIASES) {
            format = ClipboardFormats.findByAlias(alias);
            if (format != null) {
                break;
            }
        }
        if (format == null) {
            throw new IOException("The '.schem' format is not available in this WorldEdit version");
        }
        try (OutputStream out = new FileOutputStream(file);
             ClipboardWriter writer = format.getWriter(out)) {
            writer.write(clipboard);
        }
        return file;
    }

    /**
     * Writes the room's metadata file — the schema from {@code generation.md} §8.
     *
     * <p>The comments are deliberately verbose: these files are the example the map team will
     * copy for their own rooms. If what an anchor is written relative to isn't explained here,
     * it will not be read anywhere else.
     */
    public static File writeMetadata(BuiltRoom room, File directory, String name) throws IOException {
        File file = new File(directory, name + ".yml");
        try (Writer w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
            w.write("# " + name + " — TakashiDungeons room metadata\n");
            w.write("# Generated by TestRoomFactory; hand edits are overwritten by /tdungeons gen.\n");
            w.write("#\n");
            w.write("# type:   entrance | normal | boss\n");
            w.write("# weight: share in the weighted candidate draw (higher = more frequent)\n");
            w.write("# doors:  [x, y, z] — the BASE-CENTER block of the door opening,\n");
            w.write("#         relative to the room ORIGIN (NOT the schematic corner).\n");
            w.write("#         Facing is not written here; it is derived from the anchor vector.\n");
            w.write("#         List order is the door's address — which door got connected and\n");
            w.write("#         which one gets plugged is tracked by this index.\n");
            w.write("\n");
            w.write("type: " + room.type().yamlValue() + "\n");
            w.write("weight: " + room.weight() + "\n");
            w.write("\n");
            if (room.doorAnchors().isEmpty()) {
                w.write("doors: []\n");
            } else {
                w.write("doors:\n");
                for (Vec3i a : room.doorAnchors()) {
                    w.write("  - [" + a.x() + ", " + a.y() + ", " + a.z() + "]\n");
                }
            }
        }
        return file;
    }

    /**
     * Builds the standard test room set and writes it to disk ({@code .schem} + {@code .yml}).
     *
     * <p>The set is deliberately varied — each room tests one thing:
     * <ul>
     *   <li>{@code test_cross} 4 doors — the branch point (§6.3's maze feeling)</li>
     *   <li>{@code test_corridor} opposite pair — a chain segment</li>
     *   <li>{@code test_corner} adjacent pair — <b>the room that measures the rotation sign</b>;
     *       because N-S is not symmetric, clockwise can be told apart from anticlockwise</li>
     *   <li>{@code test_deadend} single door — the end of a side branch</li>
     *   <li>{@code test_entrance} single door, type {@code entrance} — the critical path's start</li>
     *   <li>{@code test_boss} single door, 33x33 — proves different sizes coexist in one dungeon</li>
     *   <li>{@code test_long} 9x25 rectangle, east door near the south end — <b>the room that
     *       breaks §4's naive absolute-value rule.</b> The naive rule says "south wall"; the
     *       correct answer is east.</li>
     *   <li>{@code test_even} 10x16 with <b>even sides</b> and offset doors — tests §9's claim
     *       that the odd-side-length rule could be dropped</li>
     * </ul>
     *
     * @return how many rooms were written
     */
    public static int writeStandardSet(File directory) throws IOException {
        record Spec(String name, int sizeX, int sizeZ, int height,
                    RoomType type, int weight, List<DoorSpec> doors) {
        }

        List<Spec> specs = List.of(
                new Spec("test_cross", 17, 17, 9, RoomType.NORMAL, 100, List.of(
                        DoorSpec.of(Door.NORTH), DoorSpec.of(Door.EAST),
                        DoorSpec.of(Door.SOUTH), DoorSpec.of(Door.WEST))),
                new Spec("test_corridor", 17, 17, 9, RoomType.NORMAL, 150, List.of(
                        DoorSpec.of(Door.NORTH), DoorSpec.of(Door.SOUTH))),
                new Spec("test_corner", 17, 17, 9, RoomType.NORMAL, 120, List.of(
                        DoorSpec.of(Door.NORTH), DoorSpec.of(Door.EAST))),
                new Spec("test_deadend", 17, 17, 9, RoomType.NORMAL, 60, List.of(
                        DoorSpec.of(Door.NORTH))),
                new Spec("test_entrance", 17, 17, 9, RoomType.ENTRANCE, 100, List.of(
                        DoorSpec.of(Door.NORTH))),
                new Spec("test_boss", 33, 33, 15, RoomType.BOSS, 100, List.of(
                        DoorSpec.of(Door.NORTH))),
                // East wall, near the south end: v = (+4, +10). Because |dz| > |dx| the naive
                // rule says SOUTH -- wrong. Normalized, nx=4/4=1.0 > nz=10/12=0.833 -> EAST.
                // Offset +10 is the ceiling: at +11 a 3-block opening spills into the corner
                // block (in a 25-long wall the last valid centre is 22). carveDoor bounds it.
                new Spec("test_long", 9, 25, 7, RoomType.NORMAL, 80, List.of(
                        DoorSpec.of(Door.NORTH), new DoorSpec(Door.EAST, 10))),
                // Even sides (10, 16) -> origin (5,0,8), box -5..4 / -8..7: asymmetric about it
                new Spec("test_even", 10, 16, 8, RoomType.NORMAL, 80, List.of(
                        new DoorSpec(Door.NORTH, 1), new DoorSpec(Door.SOUTH, -2),
                        new DoorSpec(Door.EAST, 3))));

        for (Spec spec : specs) {
            BuiltRoom room = buildRoom(spec.sizeX(), spec.sizeZ(), spec.height(),
                    spec.type(), spec.weight(), spec.doors());
            write(room.clipboard(), directory, spec.name());
            writeMetadata(room, directory, spec.name());
        }
        return specs.size();
    }

    /** {@link BlockTypes} fields are nullable; if one is missing, throw rather than silently
     *  placing air. */
    private static BlockState state(BlockType type, String name) {
        if (type == null) {
            throw new IllegalStateException("Block type not present in this version: " + name);
        }
        return type.getDefaultState();
    }
}
