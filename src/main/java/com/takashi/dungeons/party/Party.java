package com.takashi.dungeons.party;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A group of players who play together.
 *
 * <h2>The leader is a MEMBER, not a field beside the members</h2>
 * {@link #members} contains the leader, and {@link #leader} is a pointer into that set. The
 * alternative — a leader field plus a member set that excludes them — makes every size question
 * ambiguous ({@code members.size()} or {@code + 1}?) and every removal a two-place edit. Here a
 * party of three has three members, and promoting somebody moves a pointer.
 *
 * <h2>Order is join order</h2>
 * The set is a {@link LinkedHashSet} so the sidebar shows the party in the order it was built
 * rather than in whatever order a hash produced — a list that reshuffles itself between refreshes
 * is unreadable. It is also what makes "the longest-standing member" a well-defined answer when
 * the leader disconnects.
 *
 * <p>A party lives in memory only, for as long as the server is up. That is not a shortcut: player
 * data belongs in SQL (phase 7) and a party is not player data — it is a fact about right now.
 */
public final class Party {

    /**
     * Ids are never reused, exactly as instance ids are not: a party is an event, not a place, and
     * a log line about "party#3" must mean one group of people.
     */
    private final int id;

    private final Set<UUID> members = new LinkedHashSet<>();

    private UUID leader;

    private final long created = System.currentTimeMillis();

    /**
     * The dungeon this party is in, or {@code null}.
     *
     * <p>An id rather than the instance itself: an instance that has closed must not be reachable
     * through a party, and a number that no longer resolves is a dead end by construction. The
     * manager clears it anyway when the instance closes — this is the belt to that's braces.
     */
    private Integer instanceId;

    /** True while a dungeon is being generated for this party. See {@code PartyManager}. */
    private boolean opening;

    Party(int id, UUID leader) {
        this.id = id;
        this.leader = leader;
        members.add(leader);
    }

    public int id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public boolean isLeader(UUID player) {
        return leader.equals(player);
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }

    public int size() {
        return members.size();
    }

    public long createdAt() {
        return created;
    }

    /** Everyone in the party, in join order. The leader is in here. */
    public List<UUID> members() {
        return List.copyOf(members);
    }

    /**
     * Everyone, leader first.
     *
     * <p>The sidebar and {@code /party list} both want this order, and neither should have to
     * re-derive it — the leader moving to the top is a property of the party, not of one view.
     */
    public List<UUID> ordered() {
        List<UUID> ordered = new ArrayList<>(members.size());
        ordered.add(leader);
        for (UUID member : members) {
            if (!member.equals(leader)) {
                ordered.add(member);
            }
        }
        return ordered;
    }

    /** The instance this party is playing, or {@code null}. */
    public Integer instanceId() {
        return instanceId;
    }

    public boolean isOpening() {
        return opening;
    }

    void instanceId(Integer id) {
        instanceId = id;
    }

    void opening(boolean value) {
        opening = value;
    }

    boolean add(UUID player) {
        return members.add(player);
    }

    boolean remove(UUID player) {
        return members.remove(player);
    }

    void leader(UUID player) {
        leader = player;
    }

    /**
     * Who leads once the current leader is gone: the longest-standing of the rest.
     *
     * <p>Seniority rather than a random pick, because the answer has to be explainable to the
     * people it happens to — "the oldest member takes over" is a rule a party can predict, and a
     * predictable rule is what stops a disconnect from feeling like the plugin choosing sides.
     *
     * @return {@code null} when nobody else is left
     */
    UUID nextLeader() {
        for (UUID member : members) {
            if (!member.equals(leader)) {
                return member;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "party#" + id + "[leader=" + leader + ", size=" + members.size() + "]";
    }
}
