package com.takashi.dungeons.command;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.hud.HudService;
import com.takashi.dungeons.instance.DungeonInstance;
import com.takashi.dungeons.instance.InstanceManager;
import com.takashi.dungeons.party.Party;
import com.takashi.dungeons.party.PartyInvite;
import com.takashi.dungeons.party.PartyManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /party} — the player-facing party command.
 *
 * <p>Every reply here is an answer to somebody who typed something. Announcements to the group
 * come from {@link PartyManager}, which is the only place that knows who the group is; the
 * division is written down in that class.
 *
 * <p>There is no {@code create}: {@code /party invite} makes the party. And there is no offline
 * target anywhere — a disconnect takes a player out of their party, so every name this command
 * resolves has to belong to somebody who is here.
 */
public final class PartyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "invite", "accept", "deny", "join", "leave", "kick", "leader", "disband", "list",
            "hud");

    private static final List<String> STATES = List.of("on", "off");

    private final TakashiDungeonsPlugin plugin;

    public PartyCommand(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            // Not translated, and not an oversight: this reaches the console, which is a
            // diagnostic surface — the same rule the logs follow.
            sender.sendMessage(Component.text("This command must be run by a player.",
                    NamedTextColor.RED));
            return true;
        }

        PartyManager parties = plugin.getPartyManager();
        if (parties == null || !parties.isEnabled()) {
            player.sendMessage(plugin.getMessages().get("party.disabled"));
            return true;
        }

        if (args.length == 0) {
            // Bare /party shows your party if you have one, and how to make one if you do not.
            // Answering with usage either way would make the command feel like it did nothing.
            if (parties.partyOf(player) == null) {
                usage(player, label);
            } else {
                list(player, parties);
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "invite" -> invite(player, parties, label, args);
            case "accept" -> answer(player, parties, label, args, true);
            case "deny", "decline" -> answer(player, parties, label, args, false);
            case "join", "warp" -> join(player, parties);
            case "leave" -> leave(player, parties);
            case "kick", "remove" -> kick(player, parties, label, args);
            case "leader", "promote", "transfer" -> promote(player, parties, label, args);
            case "disband" -> disband(player, parties);
            case "list", "info" -> list(player, parties);
            case "hud" -> hud(player, label, args);
            default -> usage(player, label);
        }
        return true;
    }

    // ------------------------------------------------------------------ subcommands

    private void invite(Player player, PartyManager parties, String label, String[] args) {
        if (args.length < 2) {
            player.sendMessage(usageLine(label, "invite <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || !player.canSee(target)) {
            // canSee as well as null: a vanished admin must not be discoverable by inviting them.
            player.sendMessage(plugin.getMessages().get("party.target-offline",
                    Placeholder.unparsed("player", args[1])));
            return;
        }

        PartyManager.Result result = parties.invite(player, target);
        switch (result) {
            case OK -> player.sendMessage(plugin.getMessages().get("party.invite-sent",
                    Placeholder.unparsed("player", target.getName())));
            case SELF -> player.sendMessage(plugin.getMessages().get("party.invite-self"));
            case TARGET_IN_PARTY -> {
                Party mine = parties.partyOf(player);
                player.sendMessage(mine != null && mine.contains(target.getUniqueId())
                        ? plugin.getMessages().get("party.invite-already-member",
                                Placeholder.unparsed("player", target.getName()))
                        : plugin.getMessages().get("party.invite-target-busy",
                                Placeholder.unparsed("player", target.getName())));
            }
            case ALREADY_INVITED -> player.sendMessage(plugin.getMessages().get("party.invite-pending",
                    Placeholder.unparsed("player", target.getName())));
            case FULL -> player.sendMessage(plugin.getMessages().get("party.full",
                    Placeholder.unparsed("max", String.valueOf(parties.maxSize()))));
            default -> reportCommon(player, result);
        }
    }

    /**
     * {@code /party accept} and {@code /party deny} — one method, because everything up to the
     * final call is the same question: <i>which</i> invite is being answered.
     */
    private void answer(Player player, PartyManager parties, String label, String[] args,
                        boolean accept) {
        List<PartyInvite> pending = parties.pendingFor(player.getUniqueId());
        if (pending.isEmpty()) {
            player.sendMessage(plugin.getMessages().get("party.no-invite"));
            return;
        }

        PartyInvite invite;
        if (args.length >= 2) {
            invite = byInviter(pending, args[1]);
            if (invite == null) {
                player.sendMessage(plugin.getMessages().get("party.no-invite-from",
                        Placeholder.unparsed("player", args[1])));
                return;
            }
        } else if (pending.size() > 1) {
            // Picking one for them would be the plugin guessing which party they meant, and the
            // wrong guess is a dungeon run with the wrong people.
            player.sendMessage(plugin.getMessages().get("party.invite-ambiguous",
                    Placeholder.unparsed("count", String.valueOf(pending.size())),
                    Placeholder.unparsed("label", label)));
            for (PartyInvite candidate : pending) {
                player.sendMessage(plugin.getMessages().get("party.invite-ambiguous-entry",
                        Placeholder.unparsed("player", nameOf(candidate.inviter())),
                        Placeholder.unparsed("time",
                                InstanceManager.formatDuration(candidate.remainingMillis()))));
            }
            return;
        } else {
            invite = pending.get(0);
        }

        if (!accept) {
            parties.deny(player, invite);
            player.sendMessage(plugin.getMessages().get("party.deny-sent",
                    Placeholder.unparsed("player", nameOf(invite.inviter()))));
            return;
        }

        PartyManager.Result result = parties.accept(player, invite);
        switch (result) {
            // The party itself is told by the manager — this is the line for the person who
            // typed, and it is the one place where saying nothing would read as a failure.
            case OK -> player.sendMessage(plugin.getMessages().get("party.accept-sent"));
            case ALREADY_IN_PARTY ->
                    player.sendMessage(plugin.getMessages().get("party.already-in-party"));
            case EXPIRED -> player.sendMessage(plugin.getMessages().get("party.invite-too-late"));
            case PARTY_GONE -> player.sendMessage(plugin.getMessages().get("party.invite-gone"));
            case FULL -> player.sendMessage(plugin.getMessages().get("party.full",
                    Placeholder.unparsed("max", String.valueOf(parties.maxSize()))));
            default -> reportCommon(player, result);
        }
    }

    /**
     * {@code /party join} — go to the dungeon the party is in.
     *
     * <p>This is what the {@code [Join]} button in the announcement runs, and it works typed as
     * well: a player who dismissed the message, or who logged in after it was sent, still has a
     * way to catch up with their party. It is the one place a player is moved without touching a
     * gateway, and it is deliberate — a wild gateway a leader found is not somewhere the rest of
     * the party can walk to.
     */
    private void join(Player player, PartyManager parties) {
        Party party = parties.partyOf(player);
        if (party == null) {
            player.sendMessage(plugin.getMessages().get("party.not-in-party"));
            return;
        }
        Integer bound = party.instanceId();
        InstanceManager instances = plugin.getInstanceManager();
        DungeonInstance instance = bound == null ? null : instances.get(bound);
        if (instance == null || !instance.isActive()) {
            player.sendMessage(plugin.getMessages().get("party.no-dungeon"));
            return;
        }
        if (instance.contains(player.getUniqueId())) {
            player.sendMessage(plugin.getMessages().get("party.already-inside"));
            return;
        }
        if (!instances.enter(player, instance)) {
            // enter() refuses rather than throws — the dungeon may have started closing between
            // the check above and here. Saying nothing would read as a button that does nothing.
            player.sendMessage(plugin.getMessages().get("party.join-failed"));
            return;
        }
        player.sendMessage(plugin.getMessages().get("party.joined-dungeon"));
    }

    private void leave(Player player, PartyManager parties) {
        PartyManager.Result result = parties.leave(player);
        if (result == PartyManager.Result.OK) {
            player.sendMessage(plugin.getMessages().get("party.leave-sent"));
        } else {
            reportCommon(player, result);
        }
    }

    private void kick(Player player, PartyManager parties, String label, String[] args) {
        if (args.length < 2) {
            player.sendMessage(usageLine(label, "kick <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(plugin.getMessages().get("party.target-offline",
                    Placeholder.unparsed("player", args[1])));
            return;
        }
        PartyManager.Result result = parties.kick(player, target.getUniqueId(), target.getName());
        switch (result) {
            case OK -> { /* the party, and the kicked player, were told by the manager */ }
            case SELF -> player.sendMessage(plugin.getMessages().get("party.kick-self",
                    Placeholder.unparsed("label", label)));
            case NOT_MEMBER -> player.sendMessage(plugin.getMessages().get("party.not-member",
                    Placeholder.unparsed("player", target.getName())));
            default -> reportCommon(player, result);
        }
    }

    private void promote(Player player, PartyManager parties, String label, String[] args) {
        if (args.length < 2) {
            player.sendMessage(usageLine(label, "leader <player>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            player.sendMessage(plugin.getMessages().get("party.target-offline",
                    Placeholder.unparsed("player", args[1])));
            return;
        }
        PartyManager.Result result = parties.promote(player, target.getUniqueId(), target.getName());
        switch (result) {
            case OK -> { /* announced to the party */ }
            case SELF -> player.sendMessage(plugin.getMessages().get("party.leader-self"));
            case NOT_MEMBER -> player.sendMessage(plugin.getMessages().get("party.not-member",
                    Placeholder.unparsed("player", target.getName())));
            default -> reportCommon(player, result);
        }
    }

    private void disband(Player player, PartyManager parties) {
        PartyManager.Result result = parties.disband(player);
        if (result != PartyManager.Result.OK) {
            reportCommon(player, result);
        }
    }

    private void list(Player player, PartyManager parties) {
        Party party = parties.partyOf(player);
        if (party == null) {
            player.sendMessage(plugin.getMessages().get("party.not-in-party"));
            return;
        }
        player.sendMessage(plugin.getMessages().get("party.list-header",
                Placeholder.unparsed("size", String.valueOf(party.size())),
                Placeholder.unparsed("max", String.valueOf(parties.maxSize()))));
        for (UUID member : party.ordered()) {
            player.sendMessage(plugin.getMessages().get(
                    party.isLeader(member) ? "party.list-leader" : "party.list-member",
                    Placeholder.unparsed("player", nameOf(member))));
        }
    }

    /**
     * {@code /party hud [on|off]} — the party block inside the sidebar, not a second HUD.
     *
     * <p>Separate from {@code /hud} because it answers a different question: {@code /hud} is "do I
     * want a sidebar", this is "do I want my party in it". A player who turned the sidebar off
     * entirely is told so rather than silently toggling something they cannot see.
     */
    private void hud(Player player, String label, String[] args) {
        HudService hud = plugin.getHudService();
        if (hud == null || !hud.isEnabled()) {
            player.sendMessage(plugin.getMessages().get("party.hud-unavailable"));
            return;
        }
        boolean visible;
        if (args.length >= 2) {
            String state = args[1].toLowerCase(Locale.ROOT);
            if (!STATES.contains(state)) {
                player.sendMessage(usageLine(label, "hud [on|off]"));
                return;
            }
            visible = state.equals("on");
            hud.setPartyVisible(player, visible);
        } else {
            visible = hud.togglePartyBlock(player);
        }
        player.sendMessage(plugin.getMessages().get(visible ? "party.hud-on" : "party.hud-off"));
        if (visible && !hud.isVisible(player)) {
            // The block is on but the sidebar it lives in is not — otherwise this looks broken.
            player.sendMessage(plugin.getMessages().get("party.hud-sidebar-off"));
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The refusals that mean the same thing whatever was asked. Kept in one place so a new
     * subcommand cannot forget one of them and answer with silence.
     */
    private void reportCommon(Player player, PartyManager.Result result) {
        switch (result) {
            case NOT_IN_PARTY -> player.sendMessage(plugin.getMessages().get("party.not-in-party"));
            case NOT_LEADER -> player.sendMessage(plugin.getMessages().get("party.not-leader"));
            case DISABLED -> player.sendMessage(plugin.getMessages().get("party.disabled"));
            default -> player.sendMessage(plugin.getMessages().get("party.failed"));
        }
    }

    private @Nullable PartyInvite byInviter(List<PartyInvite> pending, String name) {
        for (PartyInvite invite : pending) {
            if (nameOf(invite.inviter()).equalsIgnoreCase(name)) {
                return invite;
            }
        }
        return null;
    }

    /** A player's name; falls back to the offline record, and then to a translated "someone". */
    private String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        String name = offline.getName();
        return name != null ? name : plugin.getMessages().raw("party.someone");
    }

    private void usage(Player player, String label) {
        for (String line : plugin.getMessages().list("party.usage")) {
            player.sendMessage(plugin.getMessages().get("party.usage-line",
                    Placeholder.unparsed("usage", line.replace("<label>", label))));
        }
    }

    private Component usageLine(String label, String rest) {
        return plugin.getMessages().get("party.usage-one",
                Placeholder.unparsed("usage", "/" + label + " " + rest));
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        PartyManager parties = plugin.getPartyManager();
        if (!(sender instanceof Player player) || parties == null || !parties.isEnabled()) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length != 2) {
            return List.of();
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            // Only players who could actually accept: someone already in a party cannot.
            case "invite" -> filter(Bukkit.getOnlinePlayers().stream()
                    .filter(other -> !other.equals(player))
                    .filter(player::canSee)
                    .filter(other -> parties.partyOf(other) == null)
                    .map(Player::getName)
                    .toList(), args[1]);
            case "kick", "remove", "leader", "promote", "transfer" -> {
                Party party = parties.partyOf(player);
                if (party == null || !party.isLeader(player.getUniqueId())) {
                    yield List.of();
                }
                List<String> names = new ArrayList<>();
                for (UUID member : party.members()) {
                    if (!member.equals(player.getUniqueId())) {
                        names.add(nameOf(member));
                    }
                }
                yield filter(names, args[1]);
            }
            case "hud" -> filter(STATES, args[1]);
            case "accept", "deny", "decline" -> filter(
                    parties.pendingFor(player.getUniqueId()).stream()
                            .map(invite -> nameOf(invite.inviter()))
                            .toList(), args[1]);
            default -> List.of();
        };
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
