import com.takashi.dungeons.generation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * The test room set shared by the probes.
 *
 * The values are IDENTICAL to what TestRoomFactory produces -- verifiable against the
 * generated .yml files. Why a single source: when the room set changes, updating two separate
 * probes by hand gets forgotten and the tests silently stop describing reality.
 */
public final class Rooms {

    private Rooms() {
    }

    /**
     * The full set including entrance and boss (8 rooms), in <b>ALPHABETICAL</b> order.
     *
     * The order matters and is not incidental: on the server the templates come from
     * {@code SchematicService.list()}, and that method SORTS the file names. Because
     * RoomLibrary.drawWeighted performs a cumulative weight scan, the same seed with a
     * different ORDER produces a different dungeon.
     *
     * Keeping this order identical to the server's is what lets the probes compute IN ADVANCE
     * what the server will build -- that is how the expected coordinates of the block tests
     * are derived.
     */
    public static List<RoomTemplate> all() {
        return List.of(
                t("test_boss", RoomType.BOSS, 100, box(33, 15, 33),
                        new Vec3i(0, 1, -16)),
                t("test_corner", RoomType.NORMAL, 120, box(17, 9, 17),
                        new Vec3i(0, 1, -8), new Vec3i(8, 1, 0)),
                t("test_corridor", RoomType.NORMAL, 150, box(17, 9, 17),
                        new Vec3i(0, 1, -8), new Vec3i(0, 1, 8)),
                t("test_cross", RoomType.NORMAL, 100, box(17, 9, 17),
                        new Vec3i(0, 1, -8), new Vec3i(8, 1, 0),
                        new Vec3i(0, 1, 8), new Vec3i(-8, 1, 0)),
                t("test_deadend", RoomType.NORMAL, 60, box(17, 9, 17),
                        new Vec3i(0, 1, -8)),
                t("test_even", RoomType.NORMAL, 80, new Aabb(-5, 0, -8, 4, 7, 7),
                        new Vec3i(1, 1, -8), new Vec3i(-2, 1, 7), new Vec3i(4, 1, 3)),
                t("test_entrance", RoomType.ENTRANCE, 100, box(17, 9, 17),
                        new Vec3i(0, 1, -8)),
                t("test_long", RoomType.NORMAL, 80, box(9, 7, 25),
                        new Vec3i(0, 1, -12), new Vec3i(4, 1, 10)));
    }

    /** Only the 'normal' type rooms (6 of them), in the same order as all(). */
    public static List<RoomTemplate> normalPool() {
        List<RoomTemplate> out = new ArrayList<>();
        for (RoomTemplate t : all()) {
            if (t.type() == RoomType.NORMAL) {
                out.add(t);
            }
        }
        return out;
    }

    public static RoomTemplate byName(String name) {
        for (RoomTemplate t : all()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Test odasi yok: " + name);
    }

    public static RoomTemplate t(String name, RoomType type, int weight, Aabb box,
                                 Vec3i... anchors) {
        List<DoorAnchor> doors = new ArrayList<>();
        for (int i = 0; i < anchors.length; i++) {
            doors.add(DoorAnchor.of(i, anchors[i], box));
        }
        return new RoomTemplate(name, type, weight, doors, box);
    }

    /** For an odd-sided room the origin is centred: box -n..n. */
    public static Aabb box(int sizeX, int height, int sizeZ) {
        return new Aabb(-(sizeX / 2), 0, -(sizeZ / 2),
                sizeX - 1 - sizeX / 2, height - 1, sizeZ - 1 - sizeZ / 2);
    }

    /** The slot box: X/Z from the slot, Y as wide as a world's height range. */
    public static Aabb slotBox(int size) {
        return new Aabb(0, -64, 0, size - 1, 319, size - 1);
    }
}
