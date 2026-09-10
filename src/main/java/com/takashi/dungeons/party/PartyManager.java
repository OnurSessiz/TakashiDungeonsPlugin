package com.takashi.dungeons.party;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.instance.InstanceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Creates, tracks and tears down parties, and holds the outstanding invites.
 *
 * <h2>Who says what</h2>
 * The split is the same one {@link InstanceManager} already makes. A <b>command reply</b> — "you
 * are not the leader", "that player is offline" — belongs to {@code PartyCommand}: it is an answer
 * to the sender, and the sender is standing right there. A <b>broadcast</b> — "X joined the party",
 * "the leader left" — belongs here, because membership is declared in this class and nowhere else
 * can answer "who is in this party" without asking it first. So the methods below return a
 * {@link Result} rather than talking, except where they are announcing a fact to a group.
 *
 * <h2>A party is session state, on purpose</h2>
 * Parties live in memory and a disconnect removes the player. The tempting alternative — keep the
 * membership warm for a minute in case they come back — needs a timeout clock, a rule for what a
 * dungeon does with a member who is not there, and somewhere to survive a restart. That is phase
 * 7's ground (player data is SQL, never YAML), and adding it later breaks nothing here: a
 * reconnect grace is a delay in front of {@link #handleQuit}, not a different model.
 *
 * <h2>Auto-creation</h2>
 * There is no {@code /party create}. Inviting somebody while partyless creates the party with the
 * inviter as leader — the state "leader alone, one invite in flight" has to exist anyway, so
 * making the player ask for it twice buys nothing. A party of one is legitimate and is disbanded
 * only when it empties or when its leader says so.
 */
public final class PartyManager {

    /** Why a party operation refused. Mapped to a message by the command layer. */
    public enum Result {
        OK,
        /** {@code party.enabled: false} — the system is off server-wide. */
        DISABLED,
        /** The actor is not in a party at all. */
        NOT_IN_PARTY,
        /** The actor is in a party but does not lead it. */
        NOT_LEADER,
        /** The target is not in the actor's party. */
        NOT_MEMBER,
        /** The actor tried to invite, kick or promote themselves. */
        SELF,
        /** The target already belongs to a party — theirs or someone else's. */
        TARGET_IN_PARTY,
        /** The actor already has an invite out to this target. */
        ALREADY_INVITED,
        /** The party is at {@code party.max-size}. */
        FULL,
        /** Accepting while already in a party. */
        ALREADY_IN_PARTY,
        /** The invite ran out of time between being sent and being answered. */
        EXPIRED,
        /** The party was disbanded while the invite was in flight. */
        PARTY_GONE
    }

    private final TakashiDungeonsPlugin plugin;

    /** Live parties, insertion-ordered so an admin listing reads as "oldest first". */
    private final Map<Integer, Party> parties = new LinkedHashMap<>();

    /** Player → their party. The index that makes {@link #partyOf} a lookup, not a scan. */
    private final Map<UUID, Party> byPlayer = new HashMap<>();

    /**
     * Outstanding invites: target → (party id → invite).
     *
     * <p>Keyed by target first because that is the question asked most — {@code /party accept} has
     * to know what is waiting for one player. The inner map is keyed by party rather than by
     * inviter so two leaders cannot pile invites from the same party onto one person, and so a
     * disbanded party's invite is removable in one step.
     */
    private final Map<UUID, Map<Integer, PartyInvite>> invites = new HashMap<>();

    /** Party ids are never reused — see {@link Party}. */
    private int nextId = 1;

    /** Fired with the players whose party state just changed. The sidebar hangs off this. */
    private final List<Consumer<Set<UUID>>> changeHandlers = new ArrayList<>();

    private boolean enabled;
    private int maxSize;
    private long inviteTimeoutMillis;

    /** Expires invites; see {@link #sweepInvites}. */
    private @Nullable BukkitTask sweeper;

    public PartyManager(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ lifecycle

    public void enable() {
        readConfig();
        // A second is fine: the invite countdown is shown in whole seconds, and expiry that is a
        // tick late has never mattered to anybody.
        sweeper = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::sweepInvites, 20L, 20L);
    }

    public void disable() {
        if (sweeper != null) {
            sweeper.cancel();
            sweeper = null;
        }
        // Parties are not torn down: the server is stopping and every member is about to be gone
        // anyway. Dropping the maps only matters for a /reload, and there the state SHOULD go —
        // it is session state and the session is ending.
        parties.clear();
        byPlayer.clear();
        invites.clear();
    }

    /** Re-reads {@code config.yml} → {@code party:}. Existing parties are left alone. */
    public void reload() {
        readConfig();
    }

    private void readConfig() {
        enabled = plugin.getConfig().getBoolean("party.enabled", true);
        // Below two the setting is meaningless — a "party" of one is what a player already has by
        // standing there. Clamped rather than refused, so a typo costs a comment, not the feature.
        maxSize = Math.max(2, plugin.getConfig().getInt("party.max-size", 4));
        inviteTimeoutMillis =
                Math.max(5L, plugin.getConfig().getLong("party.invite-timeout-seconds", 60)) * 1000L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int maxSize() {
        return maxSize;
    }

    // ------------------------------------------------------------------ lookup

    /** The party this player is in, or {@code null}. */
    public @Nullable Party partyOf(UUID player) {
        return byPlayer.get(player);
    }

    public @Nullable Party partyOf(Player player) {
        return partyOf(player.getUniqueId());
    }

    /** Live parties, oldest first. */
    public List<Party> all() {
        return List.copyOf(parties.values());
    }

    public int count() {
        return parties.size();
    }

    /** The invites waiting for this player, expired ones already dropped. */
    public List<PartyInvite> pendingFor(UUID target) {
        Map<Integer, PartyInvite> pending = invites.get(target);
        if (pending == null) {
            return List.of();
        }
        return pending.values().stream().filter(invite -> !invite.isExpired()).toList();
    }

    // ------------------------------------------------------------------ inviting

    /**
     * Invites a player, creating the inviter's party if they had none.
     *
     * <p>The invite message is sent from here rather than from the command: it goes to somebody
     * who did not type anything, which makes it a notification, not a reply.
     */
    public Result invite(Player inviter, Player target) {
        if (!enabled) {
            return Result.DISABLED;
        }
        if (inviter.getUniqueId().equals(target.getUniqueId())) {
            return Result.SELF;
        }
        if (partyOf(target) != null) {
            return Result.TARGET_IN_PARTY;
        }

        Party party = partyOf(inviter);
        boolean created = false;
        if (party == null) {
            party = create(inviter.getUniqueId());
            created = true;
        } else if (!party.isLeader(inviter.getUniqueId())) {
            return Result.NOT_LEADER;
        }

        // Counted against the invites in flight as well as the members present. Without it a
        // four-slot party can send four invites and end up with seven people, and the limit an
        // operator configured turns out to have been a suggestion.
        if (party.size() + outstandingFor(party) >= maxSize) {
            // A party created a moment ago for an invite that cannot be sent is litter.
            if (created) {
                dissolve(party);
            }
            return Result.FULL;
        }

        Map<Integer, PartyInvite> pending =
                invites.computeIfAbsent(target.getUniqueId(), key -> new LinkedHashMap<>());
        PartyInvite existing = pending.get(party.id());
        if (existing != null && !existing.isExpired()) {
            return Result.ALREADY_INVITED;
        }

        PartyInvite invite = new PartyInvite(party, inviter.getUniqueId(), target.getUniqueId(),
                System.currentTimeMillis() + inviteTimeoutMillis);
        pending.put(party.id(), invite);
        sendInvite(inviter, target, invite);
        if (created) {
            fireChanged(Set.of(inviter.getUniqueId()));
        }
        return Result.OK;
    }

    /**
     * The invite as the target sees it: a line with an {@code [Accept]} and a {@code [Deny]} the
     * player can click.
     *
     * <p><b>The buttons are built here, not written in the language file.</b> A translator supplies
     * their labels and their hover text; the click actions are the plugin's. Left in the file, a
     * mistyped {@code <click:run_command>} would produce an invite nobody can accept, and the fault
     * would look like a broken party system rather than a broken translation.
     */
    private void sendInvite(Player inviter, Player target, PartyInvite invite) {
        var messages = plugin.getMessages();
        Component accept = button("party.invite-accept-label", "party.invite-accept-hover",
                "/party accept " + inviter.getName());
        Component deny = button("party.invite-deny-label", "party.invite-deny-hover",
                "/party deny " + inviter.getName());

        target.sendMessage(messages.get("party.invite-received",
                Placeholder.unparsed("player", inviter.getName()),
                Placeholder.component("accept", accept),
                Placeholder.component("deny", deny)));
        target.sendMessage(messages.get("party.invite-expires", Placeholder.unparsed("time",
                InstanceManager.formatDuration(invite.remainingMillis()))));
    }

    private Component button(String labelKey, String hoverKey, String command) {
        return plugin.getMessages().get(labelKey)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(plugin.getMessages().get(hoverKey)));
    }

    /** How many people have been asked to join this party and have not answered yet. */
    private int outstandingFor(Party party) {
        int count = 0;
        for (Map<Integer, PartyInvite> pending : invites.values()) {
            PartyInvite invite = pending.get(party.id());
            if (invite != null && !invite.isExpired()) {
                count++;
            }
        }
        return count;
    }

    /** Accepts a specific invite. */
    public Result accept(Player target, PartyInvite invite) {
        if (!enabled) {
            return Result.DISABLED;
        }
        if (partyOf(target) != null) {
            return Result.ALREADY_IN_PARTY;
        }
        drop(target.getUniqueId(), invite.party().id());
        if (invite.isExpired()) {
            return Result.EXPIRED;
        }
        Party party = invite.party();
        if (!parties.containsKey(party.id())) {
            return Result.PARTY_GONE;
        }
        if (party.size() >= maxSize) {
            return Result.FULL;
        }

        party.add(target.getUniqueId());
        byPlayer.put(target.getUniqueId(), party);
        broadcast(party, plugin.getMessages().get("party.joined",
                Placeholder.unparsed("player", target.getName())));
        plugin.getLogger().info("Party joined: party#" + party.id() + " - " + target.getName()
                + " (" + party.size() + "/" + maxSize + ")");
        fireChanged(new LinkedHashSet<>(party.members()));
        return Result.OK;
    }

    /** Declines a specific invite and tells whoever sent it. */
    public Result deny(Player target, PartyInvite invite) {
        drop(target.getUniqueId(), invite.party().id());
        Player inviter = plugin.getServer().getPlayer(invite.inviter());
        if (inviter != null) {
            inviter.sendMessage(plugin.getMessages().get("party.denied",
                    Placeholder.unparsed("player", target.getName())));
        }
        return Result.OK;
    }

    private void drop(UUID target, int partyId) {
        Map<Integer, PartyInvite> pending = invites.get(target);
        if (pending == null) {
            return;
        }
        pending.remove(partyId);
        if (pending.isEmpty()) {
            invites.remove(target);
        }
    }

    /**
     * Expires invites and says so to both sides.
     *
     * <p>Both sides on purpose: the target has a clickable line in their chat that has quietly
     * stopped working, and the inviter is waiting for an answer that is never coming. Expiring in
     * silence would leave the target clicking a button that answers with "you have no invite".
     */
    private void sweepInvites() {
        if (invites.isEmpty()) {
            return;
        }
        for (UUID target : List.copyOf(invites.keySet())) {
            Map<Integer, PartyInvite> pending = invites.get(target);
            if (pending == null) {
                continue;
            }
            for (PartyInvite invite : List.copyOf(pending.values())) {
                if (!invite.isExpired()) {
                    continue;
                }
                pending.remove(invite.party().id());
                notifyExpired(invite);
            }
            if (pending.isEmpty()) {
                invites.remove(target);
            }
        }
    }

    private void notifyExpired(PartyInvite invite) {
        var messages = plugin.getMessages();
        Player inviter = plugin.getServer().getPlayer(invite.inviter());
        Player target = plugin.getServer().getPlayer(invite.target());
        if (target != null) {
            String name = inviter != null ? inviter.getName() : messages.raw("party.someone");
            target.sendMessage(messages.get("party.invite-expired-target",
                    Placeholder.unparsed("player", name)));
        }
        if (inviter != null) {
            String name = target != null ? target.getName() : messages.raw("party.someone");
            inviter.sendMessage(messages.get("party.invite-expired-inviter",
                    Placeholder.unparsed("player", name)));
        }
    }

    // ------------------------------------------------------------------ membership

    private Party create(UUID leader) {
        Party party = new Party(nextId++, leader);
        parties.put(party.id(), party);
        byPlayer.put(leader, party);
        plugin.getLogger().info("Party opened: " + party);
        return party;
    }

    /** Leaves the player's party; disbands it if that empties it. */
    public Result leave(Player player) {
        Party party = partyOf(player);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        removeMember(party, player.getUniqueId(), "party.left");
        return Result.OK;
    }

    /** Removes another member. Leader only. */
    public Result kick(Player leader, UUID target, String targetName) {
        Party party = partyOf(leader);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            return Result.NOT_LEADER;
        }
        if (leader.getUniqueId().equals(target)) {
            return Result.SELF;
        }
        if (!party.contains(target)) {
            return Result.NOT_MEMBER;
        }
        Player kicked = plugin.getServer().getPlayer(target);
        if (kicked != null) {
            kicked.sendMessage(plugin.getMessages().get("party.kicked-target"));
        }
        Integer dungeon = party.instanceId();
        removeMemberNamed(party, target, targetName, "party.kicked-broadcast");
        if (kicked != null && dungeon != null) {
            ejectIfConfigured(kicked, dungeon);
        }
        return Result.OK;
    }

    /**
     * Sends a kicked player out of the party's dungeon — only if the operator asked for it.
     *
     * <p><b>Off by default, and only for a kick.</b> Leaving a party is a social act and must not
     * cost a player the run they are in the middle of. A kick is the one case where an operator
     * might reasonably want the two joined — a leader clearing out somebody who is ruining the
     * run — so it is a switch rather than a rule, and the safe answer is the default.
     */
    private void ejectIfConfigured(Player kicked, int instanceId) {
        if (!plugin.getConfig().getBoolean("party.kick-removes-from-dungeon", false)) {
            return;
        }
        var instances = plugin.getInstanceManager();
        var instance = instances == null ? null : instances.get(instanceId);
        if (instance == null || !instance.contains(kicked.getUniqueId())) {
            return;
        }
        instances.leave(kicked, instance);
        kicked.sendMessage(plugin.getMessages().get("party.kicked-from-dungeon"));
    }

    /** Hands leadership to another member. Leader only. */
    public Result promote(Player leader, UUID target, String targetName) {
        Party party = partyOf(leader);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            return Result.NOT_LEADER;
        }
        if (leader.getUniqueId().equals(target)) {
            return Result.SELF;
        }
        if (!party.contains(target)) {
            return Result.NOT_MEMBER;
        }
        party.leader(target);
        broadcast(party, plugin.getMessages().get("party.leader-changed",
                Placeholder.unparsed("player", targetName)));
        fireChanged(new LinkedHashSet<>(party.members()));
        return Result.OK;
    }

    /** Ends the party for everybody. Leader only. */
    public Result disband(Player leader) {
        Party party = partyOf(leader);
        if (party == null) {
            return Result.NOT_IN_PARTY;
        }
        if (!party.isLeader(leader.getUniqueId())) {
            return Result.NOT_LEADER;
        }
        broadcast(party, plugin.getMessages().get("party.disbanded"));
        dissolve(party);
        return Result.OK;
    }

    /**
     * A player disconnected.
     *
     * <p>Treated exactly like leaving. The honest reason is in the class comment: keeping them a
     * member needs a clock and a rule for a dungeon holding a member who is not there, and phase 7
     * is where that belongs. What must not happen is a member who is offline being counted, shown
     * in the sidebar, or waited for.
     */
    public void handleQuit(Player player) {
        // Invites to and from someone who has left are dead either way — an invite from a player
        // who is gone cannot be answered, and one to them has nobody to reach.
        invites.remove(player.getUniqueId());
        for (Map<Integer, PartyInvite> pending : List.copyOf(invites.values())) {
            pending.values().removeIf(invite -> invite.inviter().equals(player.getUniqueId()));
        }
        invites.values().removeIf(Map::isEmpty);

        Party party = partyOf(player);
        if (party != null) {
            removeMemberNamed(party, player.getUniqueId(), player.getName(), "party.quit");
        }
    }

    private void removeMember(Party party, UUID member, String broadcastKey) {
        Player player = plugin.getServer().getPlayer(member);
        removeMemberNamed(party, member, player != null ? player.getName()
                : plugin.getMessages().raw("party.someone"), broadcastKey);
    }

    /**
     * The one path every departure goes through — leaving, being kicked, disconnecting.
     *
     * <p>The order matters. The member is taken out first, so the broadcast that follows does not
     * reach them (they are being told separately, or they are gone). Leadership is handed on only
     * after that, so the announcement lands with the party as it now stands rather than as it was.
     */
    private void removeMemberNamed(Party party, UUID member, String name, String broadcastKey) {
        party.remove(member);
        byPlayer.remove(member);
        // Everyone still here, plus the one who left: the sidebar of the person leaving has to be
        // repainted too, and after the removal above they are no longer in the party's own list.
        Set<UUID> touched = new LinkedHashSet<>(party.members());
        touched.add(member);

        if (party.size() == 0) {
            dissolve(party);
            fireChanged(touched);
            return;
        }

        broadcast(party, plugin.getMessages().get(broadcastKey,
                Placeholder.unparsed("player", name)));

        if (party.isLeader(member)) {
            UUID next = party.nextLeader();
            // nextLeader() cannot be null here — size is at least one and the departed member is
            // already out — but a null check is cheaper than a party with a dangling leader.
            if (next == null) {
                dissolve(party);
                fireChanged(touched);
                return;
            }
            party.leader(next);
            Player leader = plugin.getServer().getPlayer(next);
            broadcast(party, plugin.getMessages().get("party.leader-left",
                    Placeholder.unparsed("player", leader != null ? leader.getName()
                            : plugin.getMessages().raw("party.someone"))));
        }
        fireChanged(touched);
    }

    /** Deletes the party and every invite it had out. */
    private void dissolve(Party party) {
        for (UUID member : party.members()) {
            byPlayer.remove(member);
        }
        for (Map<Integer, PartyInvite> pending : List.copyOf(invites.values())) {
            pending.remove(party.id());
        }
        invites.values().removeIf(Map::isEmpty);
        parties.remove(party.id());
        plugin.getLogger().info("Party closed: party#" + party.id());
    }

    // ------------------------------------------------------------------ the party's dungeon

    /**
     * Remembers which dungeon this party is playing — but only if it is not already in one.
     *
     * <p>Called from the gateway whenever a member gets inside, however they got there: a leader
     * opening a fresh dungeon and a member walking into a friend's already-standing one are the
     * same fact from the party's side. First one in wins; a party is in one dungeon at a time,
     * and a second binding would silently move everybody else's {@code /party join} to a place
     * their party mates are not.
     *
     * @return {@code true} when this call is what bound it — the caller uses that to announce it
     *         exactly once, rather than on every member who follows
     */
    public boolean bindIfFree(Party party, int instanceId) {
        if (party.instanceId() != null) {
            return false;
        }
        party.instanceId(instanceId);
        plugin.getLogger().info("Party entered: party#" + party.id() + " - instance#" + instanceId);
        return true;
    }

    /** The dungeon closed. Every party that was in it is loose again. */
    public void unbindInstance(int instanceId) {
        for (Party party : parties.values()) {
            if (Integer.valueOf(instanceId).equals(party.instanceId())) {
                party.instanceId(null);
            }
        }
    }

    /**
     * Claims the party for a generation that is about to start.
     *
     * <p>Generation is asynchronous and takes long enough for a second member to click a second
     * gateway. Without this claim that click passes the "is the party already in a dungeon?" test
     * — nothing is bound yet — and the party ends up holding two slots and standing in different
     * rooms. Mirrors the per-gateway guard the portal layer already keeps.
     *
     * @return {@code false} when a dungeon is already being opened for this party
     */
    public boolean beginOpening(Party party) {
        if (party.isOpening()) {
            return false;
        }
        party.opening(true);
        return true;
    }

    public void endOpening(Party party) {
        party.opening(false);
    }

    /**
     * Tells the rest of the party there is a dungeon to come to, with a button that takes them.
     *
     * <p>A button rather than a teleport. Pulling somebody out of whatever they were doing because
     * their leader clicked a block is the kind of help nobody asks for twice — and a member who is
     * mid-fight elsewhere would arrive dead.
     */
    public void announceDungeon(Party party, Player opener) {
        Component join = plugin.getMessages().get("party.dungeon-join-label")
                .clickEvent(ClickEvent.runCommand("/party join"))
                .hoverEvent(HoverEvent.showText(plugin.getMessages().get("party.dungeon-join-hover")));
        Component line = plugin.getMessages().get("party.dungeon-opened",
                Placeholder.unparsed("player", opener.getName()),
                Placeholder.component("join", join));
        for (UUID member : party.members()) {
            if (member.equals(opener.getUniqueId())) {
                continue;   // they are standing in it
            }
            Player player = plugin.getServer().getPlayer(member);
            if (player != null) {
                player.sendMessage(line);
            }
        }
    }

    // ------------------------------------------------------------------ talking to a party

    /**
     * Says something to everyone in one party.
     *
     * <p>Public for the same reason {@code InstanceManager.broadcast} is: "who is in this party" is
     * a question only this class can answer, and phase 5B's shared entry has to announce itself.
     */
    public void broadcast(Party party, Component message) {
        for (UUID member : party.members()) {
            Player player = plugin.getServer().getPlayer(member);
            if (player != null) {
                player.sendMessage(message);
            }
        }
    }

    // ------------------------------------------------------------------ the change seam

    /**
     * Registers a listener for "these players' party state changed".
     *
     * <p>Players rather than the party, because that is the question every consumer actually has.
     * The sidebar (5C) needs to repaint the person who just left as much as the four who stayed,
     * and a party object no longer contains them by the time anybody is told.
     */
    public void onChanged(Consumer<Set<UUID>> handler) {
        changeHandlers.add(handler);
    }

    private void fireChanged(Set<UUID> players) {
        for (Consumer<Set<UUID>> handler : changeHandlers) {
            try {
                handler.accept(players);
            } catch (RuntimeException e) {
                // One listener throwing must not leave the rest of them stale — the same rule the
                // instance close handlers follow.
                plugin.getLogger().warning("A party change listener threw: " + e);
            }
        }
    }

    /** Everyone online in this party, for the callers that want players rather than ids. */
    public List<Player> onlineMembers(Party party) {
        List<Player> online = new ArrayList<>(party.size());
        for (UUID member : party.ordered()) {
            Player player = plugin.getServer().getPlayer(member);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    /** Used by the admin listing; keeps {@link #parties} out of reach. */
    public Collection<UUID> membersOf(int partyId) {
        Party party = parties.get(partyId);
        return party == null ? List.of() : party.ordered();
    }
}
