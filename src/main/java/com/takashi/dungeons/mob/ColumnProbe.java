package com.takashi.dungeons.mob;

/**
 * The only thing {@link RoomSpawnFinder} is allowed to ask about the world.
 *
 * <h2>Why this interface exists</h2>
 * So the spawn search stays <b>pure Java</b> and can be tested without a server, exactly like the
 * {@code generation} package ({@code CLAUDE.md}: "geometriye dokunduysan geo-probe koş, sunucu
 * açmadan önce"). A flood fill over room geometry is the kind of code that is either right on an
 * L-shaped hall or silently wrong on one, and "silently wrong" is not something a server test
 * catches — every room still gets mobs, they are just in the wrong places.
 *
 * <p>{@code WorldColumnProbe} is the one-line Bukkit implementation; {@code SpawnProbe} in
 * {@code scripts/geo-probe} implements it over hand-drawn room shapes.
 */
public interface ColumnProbe {

    /** Whether a mob can stand <b>on</b> this block. */
    boolean isFloor(int x, int y, int z);

    /**
     * Whether this block is empty enough for a mob to occupy.
     *
     * <p>Separate from {@code !isFloor}: a block can be neither floor nor free space. Water is not
     * solid, so it is not floor, but a mob standing in it has not been placed on land — and lava
     * is the same test with a worse outcome.
     */
    boolean isClear(int x, int y, int z);
}
