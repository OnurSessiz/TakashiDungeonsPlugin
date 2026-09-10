package com.takashi.dungeons.hud;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.party.Party;
import com.takashi.dungeons.party.PartyManager;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The sidebar HUD.
 *
 * <p>Every player gets their OWN scoreboard — a shared one would force the same lines on
 * everybody, and the lines are per-player by definition (name, coin, XP). The lines are not
 * written as score entries either: each line is a {@link Team} whose entry is an invisible
 * colour code and whose prefix carries the text. That is what keeps the sidebar from
 * flickering — a refresh rewrites a team prefix instead of removing and re-adding a score.
 *
 * <p>The layout lives in {@code lang/<code>.yml} as MiniMessage lines, so an operator can rebuild
 * the HUD without touching code — and so a translator can reach the words in it, which is why it
 * is there rather than in {@code config.yml}. Values arrive as MiniMessage tags: {@code <player>},
 * {@code <coin>}, {@code <xp>} and {@code <rank>} come from the player and are inserted
 * unparsed (a tag typed into a name can never become markup), while {@code <server>} and
 * {@code <ip>} are operator-supplied and ARE parsed — that is how they get to be styled.
 *
 * <p>Who has the HUD open is held in memory, for this session only. It is player data, and
 * player data belongs in SQL (phase 7) — never in a YAML file.
 */
public final class HudService implements Listener {

    /** Objective names are capped at 16 characters. */
    private static final String OBJECTIVE = "td_hud";

    /**
     * A line's score entry has to be unique and invisible; a bare colour code is both.
     * Sixteen of them exist, so the sidebar is capped at sixteen lines.
     */
    private static final String CODES = "0123456789abcdef";

    /** Shown until the economy and rank systems exist. A zero would read as a real balance. */
    private static final String PLACEHOLDER = "-";

    /**
     * A layout line that is nothing but this marker is replaced by the party block.
     *
     * <p>A marker rather than a fixed position, because where the block sits is a layout decision
     * and layout belongs to whoever wrote {@code hud.lines}. It is also why the block is silent
     * when the marker is absent: appending it anyway would rearrange a sidebar the operator had
     * already arranged. One log line at load says the marker is missing.
     */
    private static final String PARTY_MARKER = "<party>";

    /** Stand-in values used only to check a configured line at load time. */
    private static final TagResolver PROBE = TagResolver.resolver(
            Placeholder.unparsed("player", "Player"),
            Placeholder.unparsed("coin", PLACEHOLDER),
            Placeholder.unparsed("xp", PLACEHOLDER),
            Placeholder.unparsed("rank", PLACEHOLDER),
            Placeholder.unparsed("server", "Server"),
            Placeholder.unparsed("ip", "0.0.0.0"));

    private final TakashiDungeonsPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /**
     * A player's scoreboard together with the number of lines it was built for.
     *
     * <p>The count has to travel with the board because the party block makes the line count a
     * per-player, per-moment number: a board built for eight lines cannot show nine when somebody
     * joins the party. A mismatch rebuilds rather than patches — teams and scores are created once
     * per line at construction.
     */
    private record Board(Scoreboard board, int lines) {
    }

    /** The scoreboard handed to each online player who currently sees the HUD. */
    private final Map<UUID, Board> boards = new HashMap<>();

    /** Per-player on/off. Absent = follow {@code show-by-default}. Session-scoped. */
    private final Map<UUID, Boolean> visibility = new HashMap<>();

    /** Per-player on/off for the party block alone. Absent = follow {@code party.hud-by-default}. */
    private final Map<UUID, Boolean> partyVisibility = new HashMap<>();

    private boolean enabled;
    private boolean showByDefault;
    private boolean partyByDefault;
    private int refreshTicks;
    private String serverName = "";
    private String serverIp = "";
    /** The two above, already parsed — see {@link #parse(String)} for why they are cached. */
    private Component serverNameText = Component.empty();
    private Component serverIpText = Component.empty();
    private List<String> layout = List.of();

    private @Nullable BukkitTask task;

    public HudService(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /** Called once during enable. Registers the listener; {@link #reload()} does not. */
    public void enable() {
        readConfig();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Subscribed here and NOT in reload(): reload() runs on every /tdungeons reload, and a
        // second subscription would repaint every sidebar twice per change, for ever.
        if (plugin.getPartyManager() != null) {
            plugin.getPartyManager().onChanged(this::refresh);
        }
        schedule();
        Bukkit.getOnlinePlayers().forEach(this::update);
    }

    /** Restores everyone's main scoreboard — a leftover sidebar would survive a reload. */
    public void disable() {
        stopAndClear();
    }

    /**
     * Re-reads the config and rebuilds every open sidebar. The boards are thrown away rather
     * than updated in place, because the line COUNT may have changed and a board is built for
     * a fixed number of lines. Who had it open is deliberately kept.
     */
    public void reload() {
        stopAndClear();
        readConfig();
        schedule();
        Bukkit.getOnlinePlayers().forEach(this::update);
    }

    private void stopAndClear() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : List.copyOf(boards.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                clear(player);
            }
        }
        boards.clear();
    }

    private void readConfig() {
        FileConfiguration config = plugin.getConfig();
        // An install that predates the HUD has no 'hud' section on disk. Bukkit still serves
        // the jar's defaults, so the sidebar works out of the box — but say so, otherwise the
        // operator edits config.yml looking for a section that is not there.
        if (!config.contains("hud", true)) {
            plugin.getLogger().info("config.yml has no 'hud' section - the jar's defaults are in "
                    + "use. Copy the 'hud' section out of the jar's config.yml to change them; "
                    + "the sidebar's LINES live in lang/<code>.yml.");
        }
        enabled = config.getBoolean("hud.enabled", true);
        showByDefault = config.getBoolean("hud.show-by-default", true);
        // Under party:, not under hud:, on purpose — it is the party system's block, and an
        // operator turning parties off should not have to remember a second switch elsewhere.
        partyByDefault = config.getBoolean("party.hud-by-default", true);
        refreshTicks = Math.max(1, config.getInt("hud.refresh-ticks", 20));
        serverName = config.getString("hud.server-name", "TAKASHI");
        serverIp = config.getString("hud.server-ip", "");
        serverNameText = parse(serverName);
        serverIpText = parse(serverIp);

        // The LAYOUT comes from the language file, not from here: the words in it - Player, Coin,
        // Rank - are exactly the words that need translating. The VALUES it shows (server name,
        // IP) stay in config.yml, because they are this server's, not this language's.
        List<String> lines = plugin.getMessages().list("hud.lines");
        if (lines.size() > CODES.length()) {
            plugin.getLogger().warning("hud.lines has " + lines.size() + " lines; a scoreboard "
                    + "carries at most " + CODES.length() + ", the rest were dropped.");
            lines = lines.subList(0, CODES.length());
        }
        layout = List.copyOf(lines.stream().filter(this::parses).toList());

        // An install that predates the party block has a hud.lines on disk with no marker in it —
        // and that file is never overwritten, by design. Without this line the block would simply
        // fail to appear and look like a broken feature.
        if (layout.stream().noneMatch(line -> line.strip().equals(PARTY_MARKER))) {
            plugin.getLogger().info("hud.lines has no \"" + PARTY_MARKER + "\" line, so the party "
                    + "block is not shown. Add \"" + PARTY_MARKER + "\" to hud.lines in lang/"
                    + plugin.getMessages().language() + ".yml where you want it, or delete that "
                    + "file and restart to regenerate it.");
        }
    }

    /**
     * Broken MiniMessage is caught here, once, instead of every refresh: a bad line would
     * otherwise throw on the timer and flood the console. The line is dropped and named, so
     * the operator learns which one it was.
     */
    private boolean parses(String line) {
        if (line.strip().equals(PARTY_MARKER)) {
            return true;   // not a message — it is replaced before anything is deserialized
        }
        try {
            mini.deserialize(line, PROBE);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("A hud.lines entry could not be parsed and was skipped: " + line
                    + " (" + e.getMessage() + ")");
            return false;
        }
    }

    private void schedule() {
        if (!enabled || layout.isEmpty()) {
            return;
        }
        // The lines are static today; the timer exists because coin/XP will not be.
        task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> Bukkit.getOnlinePlayers().forEach(this::update), refreshTicks, refreshTicks);
    }

    /** {@code true} when this player should be seeing the sidebar right now. */
    public boolean isVisible(Player player) {
        return enabled && !layout.isEmpty()
                && visibility.getOrDefault(player.getUniqueId(), showByDefault);
    }

    /** {@code true} when this player wants the party block inside their sidebar. */
    public boolean isPartyVisible(Player player) {
        return partyVisibility.getOrDefault(player.getUniqueId(), partyByDefault);
    }

    /** Flips the party block alone — {@code /party hud}. Returns the new state. */
    public boolean togglePartyBlock(Player player) {
        boolean next = !isPartyVisible(player);
        setPartyVisible(player, next);
        return next;
    }

    public void setPartyVisible(Player player, boolean visible) {
        partyVisibility.put(player.getUniqueId(), visible);
        update(player);
    }

    /**
     * Repaints these players now instead of at the next tick of the timer.
     *
     * <p>Hung off {@code PartyManager.onChanged}. A second of staleness would be tolerable for
     * coin or XP; it is not for a member list, because the player watching it just pressed the
     * button that changed it.
     */
    public void refresh(Collection<UUID> players) {
        for (UUID id : players) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                update(player);
            }
        }
    }

    /** Flips this player's HUD and applies it immediately. Returns the new state. */
    public boolean toggle(Player player) {
        boolean next = !isVisible(player);
        setVisible(player, next);
        return next;
    }

    /** Forces a state instead of flipping it — used by {@code /hud on|off}. */
    public void setVisible(Player player, boolean visible) {
        visibility.put(player.getUniqueId(), visible);
        update(player);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerIp() {
        return serverIp;
    }

    /** Writes the server name into config.yml and repaints every open sidebar. */
    public void setServerName(String value) {
        serverName = value;
        serverNameText = parse(value);
        plugin.getConfig().set("hud.server-name", value);
        plugin.saveConfig();
        Bukkit.getOnlinePlayers().forEach(this::update);
    }

    /** Writes the server IP into config.yml and repaints every open sidebar. */
    public void setServerIp(String value) {
        serverIp = value;
        serverIpText = parse(value);
        plugin.getConfig().set("hud.server-ip", value);
        plugin.saveConfig();
        Bukkit.getOnlinePlayers().forEach(this::update);
    }

    /** How many lines the configured layout has — reported by {@code /tdungeons hud}. */
    public int lineCount() {
        return layout.size();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        update(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Nothing to restore — the player is leaving — but the maps must not grow forever.
        boards.remove(event.getPlayer().getUniqueId());
        visibility.remove(event.getPlayer().getUniqueId());
        partyVisibility.remove(event.getPlayer().getUniqueId());
    }

    private void update(Player player) {
        if (!isVisible(player)) {
            clear(player);
            return;
        }

        List<Component> lines = renderLines(player);
        if (lines.isEmpty()) {
            clear(player);
            return;
        }

        Board handle = boards.get(player.getUniqueId());
        if (handle == null || handle.lines() != lines.size()
                || handle.board().getObjective(OBJECTIVE) == null) {
            handle = new Board(createBoard(lines.size()), lines.size());
            boards.put(player.getUniqueId(), handle);
        }

        Objective objective = handle.board().getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        objective.displayName(title());

        for (int i = 0; i < lines.size(); i++) {
            Team team = handle.board().getTeam("td_l" + i);
            if (team != null) {
                team.prefix(lines.get(i));
            }
        }

        if (player.getScoreboard() != handle.board()) {
            player.setScoreboard(handle.board());
        }
    }

    /**
     * The sidebar as this player should see it right now.
     *
     * <p>Rendered rather than looked up, because the {@link #PARTY_MARKER} line does not turn into
     * one line: it turns into as many lines as the player has party members, or into none at all.
     * That is what makes the line count per-player and the board rebuildable.
     */
    private List<Component> renderLines(Player player) {
        TagResolver values = values(player);
        List<Component> lines = new ArrayList<>(layout.size());
        for (String line : layout) {
            if (line.strip().equals(PARTY_MARKER)) {
                appendParty(player, lines);
                continue;
            }
            lines.add(mini.deserialize(line, values));
        }
        // The cap is re-applied here rather than only at load: the party block is added after the
        // layout was measured, and a big party under a long layout can push past sixteen.
        return lines.size() > CODES.length() ? lines.subList(0, CODES.length()) : lines;
    }

    /**
     * The party block: a header and one line per member, leader first.
     *
     * <p>Nothing at all when there is no party — not an empty header, not a "Party (0)". A block
     * that is always there but usually empty costs two lines of everybody's sidebar for the sake
     * of a state that says nothing.
     *
     * <p>The three lines come through {@code Messages.get}, which swallows a broken tag and warns
     * once per key. That matters here more than anywhere: this runs once per player per second.
     */
    private void appendParty(Player player, List<Component> out) {
        PartyManager parties = plugin.getPartyManager();
        if (parties == null || !parties.isEnabled() || !isPartyVisible(player)) {
            return;
        }
        Party party = parties.partyOf(player);
        if (party == null) {
            return;
        }

        // An empty header is how an operator turns the header off without losing the block.
        if (!plugin.getMessages().raw("hud.party-header").isBlank()) {
            out.add(plugin.getMessages().get("hud.party-header",
                    Placeholder.unparsed("size", String.valueOf(party.size())),
                    Placeholder.unparsed("max", String.valueOf(parties.maxSize()))));
        }
        for (UUID member : party.ordered()) {
            Player other = Bukkit.getPlayer(member);
            if (other == null) {
                // A disconnect takes a player out of the party, so this is only ever the same
                // tick's race. Skipped rather than shown greyed out: there is no offline member.
                continue;
            }
            out.add(plugin.getMessages().get(
                    party.isLeader(member) ? "hud.party-leader" : "hud.party-member",
                    Placeholder.unparsed("player", other.getName()),
                    Placeholder.unparsed("health", hearts(other.getHealth())),
                    Placeholder.unparsed("max-health", hearts(maxHealth(other)))));
        }
    }

    /** Health in hearts, rounded up — the unit a player actually reads off their own bar. */
    private static String hearts(double health) {
        return String.valueOf((int) Math.ceil(Math.max(0.0, health) / 2.0));
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }

    private void clear(Player player) {
        boolean hadBoard = boards.remove(player.getUniqueId()) != null;
        if (hadBoard || player.getScoreboard().getObjective(OBJECTIVE) != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private Scoreboard createBoard(int lineCount) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective =
                board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Without this every line carries a red number on the right — the HUD is not a counter.
        objective.numberFormat(NumberFormat.blank());

        for (int i = 0; i < lineCount; i++) {
            String entry = entry(i);
            Team team = board.registerNewTeam("td_l" + i);
            team.addEntry(entry);
            // Descending scores, so the first configured line ends up at the top.
            objective.getScore(entry).setScore(lineCount - i);
        }
        return board;
    }

    private static String entry(int index) {
        return "§" + CODES.charAt(index);
    }

    /**
     * Plain text typed by an operator comes out gold and bold; a name written with MiniMessage
     * tags keeps its own colours, because {@code colorIfAbsent} only fills in what is missing.
     */
    private Component title() {
        return serverNameText
                .colorIfAbsent(NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, TextDecoration.State.TRUE);
    }

    /**
     * Parses an operator-supplied string once and keeps the result. Once, because a broken
     * tag must not throw on every refresh; and because a name typed with an unclosed tag
     * should still show up as text rather than blanking the HUD.
     */
    private Component parse(String raw) {
        try {
            return mini.deserialize(raw);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("HUD text could not be parsed as MiniMessage and is shown as plain "
                    + "text: " + raw + " (" + e.getMessage() + ")");
            return Component.text(raw);
        }
    }

    private TagResolver values(Player player) {
        return TagResolver.resolver(
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("coin", coin(player)),
                Placeholder.unparsed("xp", xp(player)),
                Placeholder.unparsed("rank", rank(player)),
                // Operator-supplied, so styling tags inside them are meant to be honoured —
                // they were parsed at load time rather than on every refresh.
                Placeholder.component("server", serverNameText),
                Placeholder.component("ip", serverIpText));
    }

    // The three below are the seams the later phases plug into: coin comes from the economy
    // (phase 7 / TakashiMarket), XP and rank from TakashiRanks (phase 11). Until then they
    // are a dash, and nothing else in the HUD has to change when they start returning values.

    private String coin(Player player) {
        return PLACEHOLDER;
    }

    private String xp(Player player) {
        return PLACEHOLDER;
    }

    private String rank(Player player) {
        return PLACEHOLDER;
    }
}
