package com.takashi.dungeons.portal;

/**
 * Whether a portal can be used right now.
 *
 * <p>Unlike {@code InstanceState} this is <b>not</b> one-way: a lobby portal cycles
 * {@code READY → OCCUPIED → COOLDOWN → READY} for as long as the server runs. What must not
 * happen is two dungeons hanging off one portal, and {@code OCCUPIED} is what prevents it.
 */
public enum PortalState {

    /** Nothing bound; a right-click opens a fresh dungeon. */
    READY("hazır"),

    /** A dungeon is bound and standing; a right-click joins that one. */
    OCCUPIED("dolu"),

    /** Its dungeon ended and it is waiting for the refresh hour. Lobby portals only. */
    COOLDOWN("bekliyor");

    private final String displayName;

    PortalState(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
