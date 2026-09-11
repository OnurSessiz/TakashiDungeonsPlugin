package com.takashi.dungeons.player;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One player's saved state while they are online.
 *
 * <h2>A setting has three states, not two</h2>
 * {@code null} is not "off". It means the player has never expressed a preference, and it is the
 * only state that follows {@code config.yml}: an operator who flips {@code hud.show-by-default}
 * moves everyone who has never touched {@code /hud}, and moves nobody who has. Collapsing the three
 * states into a boolean at first join would freeze the entire player base onto whatever the switch
 * said the day they first logged in.
 *
 * <h2>{@link #isPersisted()} is the permission to write</h2>
 * A profile whose load <b>failed</b> — the database was down for that one query — holds defaults
 * that look exactly like a brand-new player's. Writing it back would replace a real row with those
 * defaults and destroy the player's settings and counters. So a failed load is marked, never
 * written, and the session simply behaves as it did before phase 7: in memory, until logout.
 *
 * <p>A player with no row yet is a <b>successful</b> load of nothing, and is perfectly writable.
 *
 * <h2>Why synchronized rather than volatile fields</h2>
 * Three threads reach this object: the login thread loads it, the main thread reads and toggles it,
 * and the storage thread drains it. The methods are short and called at most a few times a second,
 * so the lock costs nothing measurable and removes every question about which half of a two-field
 * update another thread might see.
 */
public final class PlayerProfile {

    private final UUID uuid;

    private String name;
    private @Nullable Boolean hud;
    private @Nullable Boolean partyHud;
    private long firstSeen;

    private boolean persisted;
    private boolean settingsDirty;

    /** Counters as they stood at load, plus everything flushed since. For display only. */
    private PlayerStats totals = PlayerStats.ZERO;

    /** What has happened since the last flush, waiting to be added to the row. */
    private PlayerStats pending = PlayerStats.ZERO;

    public PlayerProfile(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.firstSeen = System.currentTimeMillis();
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized String name() {
        return name;
    }

    public synchronized void name(String value) {
        if (!value.equals(name)) {
            name = value;
            settingsDirty = true;
        }
    }

    /** Marks this profile as backed by a real row — the row may not have existed yet. */
    public synchronized void loaded(@Nullable Boolean hud, @Nullable Boolean partyHud,
                                    long firstSeen, PlayerStats totals) {
        this.hud = hud;
        this.partyHud = partyHud;
        if (firstSeen > 0) {
            this.firstSeen = firstSeen;
        }
        this.totals = totals;
        this.persisted = true;
        // The row has to be touched at least once so that last_seen and the name are current, and
        // so that a first-time player gets a row at all.
        this.settingsDirty = true;
    }

    /** The load threw. The profile stays usable and is never written back. */
    public synchronized void loadFailed() {
        persisted = false;
    }

    public synchronized boolean isPersisted() {
        return persisted;
    }

    public synchronized @Nullable Boolean hud() {
        return hud;
    }

    public synchronized void hud(boolean value) {
        hud = value;
        settingsDirty = true;
    }

    public synchronized @Nullable Boolean partyHud() {
        return partyHud;
    }

    public synchronized void partyHud(boolean value) {
        partyHud = value;
        settingsDirty = true;
    }

    public synchronized long firstSeen() {
        return firstSeen;
    }

    public synchronized boolean isSettingsDirty() {
        return settingsDirty;
    }

    /**
     * Clears the dirty mark <b>before</b> the write is sent, not after it succeeds.
     *
     * <p>The alternative loses changes: a toggle that lands while the write is in flight would have
     * its mark wiped by the write that did not contain it. Clearing first means a failed write costs
     * one lost setting, while clearing last would cost a setting on every successful one.
     */
    public synchronized void settingsWritten() {
        settingsDirty = false;
    }

    /** The write failed — put the mark back so the next flush tries again. */
    public synchronized void settingsFailed() {
        settingsDirty = true;
    }

    public synchronized void add(PlayerStats delta) {
        pending = pending.plus(delta);
    }

    /** Takes the pending deltas out and folds them into the displayed totals. */
    public synchronized PlayerStats drain() {
        PlayerStats drained = pending;
        pending = PlayerStats.ZERO;
        totals = totals.plus(drained);
        return drained;
    }

    /** Puts a failed write's deltas back, so the next flush tries again. */
    public synchronized void restore(PlayerStats delta) {
        pending = pending.plus(delta);
        totals = new PlayerStats(
                totals.runsEntered() - delta.runsEntered(),
                totals.runsCleared() - delta.runsCleared(),
                totals.bossKills() - delta.bossKills(),
                totals.mobKills() - delta.mobKills(),
                totals.deaths() - delta.deaths(),
                totals.secondsInside() - delta.secondsInside());
    }

    /** Everything recorded so far, flushed or not — what {@code /tdungeons stats} shows. */
    public synchronized PlayerStats stats() {
        return totals.plus(pending);
    }
}
