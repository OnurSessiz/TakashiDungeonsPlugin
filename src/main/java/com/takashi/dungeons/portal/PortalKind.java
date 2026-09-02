package com.takashi.dungeons.portal;

/**
 * Where a portal came from — which decides what happens to it once its dungeon ends.
 *
 * <p>This is the "in the wild it disappears, in the lobby it resets" rule from the roadmap. The
 * difference is not cosmetic: a wild portal is a <b>find</b>, and a find that respawned on the
 * spot would stop being one. A lobby portal is <b>furniture</b>, and furniture that vanished
 * after one use would leave the lobby with a hole in it.
 */
public enum PortalKind {

    /** Spawned out in the world by the plugin. Consumed when its dungeon ends. */
    WILD("doğa"),

    /** Placed by an operator at a fixed point. Comes back at the next refresh hour. */
    LOBBY("lobby");

    private final String displayName;

    PortalKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static PortalKind parse(String value) {
        if (value == null) {
            return null;
        }
        for (PortalKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value) || kind.displayName.equalsIgnoreCase(value)) {
                return kind;
            }
        }
        return null;
    }
}
