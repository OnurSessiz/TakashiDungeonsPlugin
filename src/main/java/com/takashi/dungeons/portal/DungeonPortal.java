package com.takashi.dungeons.portal;

import com.takashi.dungeons.generation.DungeonSize;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One dungeon entrance standing in the world: a block, the things floating over it, and the
 * dungeon it is currently bound to.
 *
 * <h2>The visual is deliberately three entities and a block</h2>
 * The block is what a player can actually hit with a right-click. The floating shard and the
 * label are {@code Display} entities — no hitbox, no gravity, no collision, and no AI to tick.
 * That is exactly why a fourth piece exists: because displays cannot be clicked, a player
 * aiming at the shard would click straight through it, so an {@code Interaction} entity provides
 * the hitbox the eye expects to be there.
 *
 * <p>The whole look is a placeholder (amethyst block, amethyst shard, the word "Dungeon") and is
 * driven from config, so replacing it later is an edit rather than a rewrite.
 */
public final class DungeonPortal {

    private final int id;
    private final Location block;
    private final PortalKind kind;
    private final String theme;
    private final DungeonSize size;

    /** What stood here before, so removing the portal leaves no trace. */
    private final BlockData previousBlock;

    private UUID itemDisplay;
    private UUID textDisplay;
    private UUID interaction;

    private PortalState state = PortalState.READY;
    private Integer boundInstanceId;

    /** When a {@code COOLDOWN} portal becomes usable again; epoch millis. */
    private long readyAt;

    /** Accumulated spin of the floating item, in degrees. */
    private float spin;

    DungeonPortal(int id, Location block, PortalKind kind, @Nullable String theme,
                  DungeonSize size, BlockData previousBlock) {
        this.id = id;
        this.block = block;
        this.kind = kind;
        this.theme = theme;
        this.size = size;
        this.previousBlock = previousBlock;
    }

    public int id() {
        return id;
    }

    /** The portal's block position — also its identity, since two cannot share one block. */
    public Location block() {
        return block;
    }

    /** The centre of the portal block, for placing the floating pieces. */
    public Location center() {
        return block.clone().add(0.5, 0.0, 0.5);
    }

    public PortalKind kind() {
        return kind;
    }

    /** The theme to generate from; {@code null} means "resolve it at use time". */
    public @Nullable String theme() {
        return theme;
    }

    public DungeonSize size() {
        return size;
    }

    public BlockData previousBlock() {
        return previousBlock;
    }

    public PortalState state() {
        return state;
    }

    void state(PortalState state) {
        this.state = state;
    }

    public @Nullable Integer boundInstanceId() {
        return boundInstanceId;
    }

    void bind(Integer instanceId) {
        this.boundInstanceId = instanceId;
    }

    public long readyAt() {
        return readyAt;
    }

    void readyAt(long readyAt) {
        this.readyAt = readyAt;
    }

    public @Nullable UUID itemDisplay() {
        return itemDisplay;
    }

    public @Nullable UUID textDisplay() {
        return textDisplay;
    }

    public @Nullable UUID interaction() {
        return interaction;
    }

    void entities(UUID itemDisplay, UUID textDisplay, UUID interaction) {
        this.itemDisplay = itemDisplay;
        this.textDisplay = textDisplay;
        this.interaction = interaction;
    }

    /** Advances and returns the floating item's rotation. */
    float nextSpin(float degrees) {
        spin = (spin + degrees) % 360f;
        return spin;
    }

    /** Whether this entity is one of the portal's own — used by the orphan sweep. */
    public boolean owns(UUID entity) {
        return entity.equals(itemDisplay) || entity.equals(textDisplay) || entity.equals(interaction);
    }

    @Override
    public String toString() {
        return "portal#" + id + " (" + kind.displayName() + ", " + size.key() + ") @ "
                + block.getWorld().getName() + " " + block.getBlockX() + ","
                + block.getBlockY() + "," + block.getBlockZ();
    }
}
