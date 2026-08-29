package com.takashi.dungeons.hud;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
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
 * <p>The layout lives in {@code config.yml} as MiniMessage lines, so an operator can rebuild
 * the HUD without touching code. Values arrive as MiniMessage tags: {@code <player>},
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

    /** The scoreboard handed to each online player who currently sees the HUD. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    /** Per-player on/off. Absent = follow {@code show-by-default}. Session-scoped. */
    private final Map<UUID, Boolean> visibility = new HashMap<>();

    private boolean enabled;
    private boolean showByDefault;
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
            plugin.getLogger().info("config.yml'de 'hud' bölümü yok — gömülü varsayılanlar "
                    + "kullanılıyor. Düzeni değiştirmek için jar içindeki config.yml'den "
                    + "'hud' bölümünü kopyala.");
        }
        enabled = config.getBoolean("hud.enabled", true);
        showByDefault = config.getBoolean("hud.show-by-default", true);
        refreshTicks = Math.max(1, config.getInt("hud.refresh-ticks", 20));
        serverName = config.getString("hud.server-name", "TAKASHI");
        serverIp = config.getString("hud.server-ip", "");
        serverNameText = parse(serverName);
        serverIpText = parse(serverIp);

        List<String> lines = config.getStringList("hud.lines");
        if (lines.size() > CODES.length()) {
            plugin.getLogger().warning("hud.lines " + lines.size() + " satır içeriyor; scoreboard "
                    + "en fazla " + CODES.length() + " satır taşır, fazlası atıldı.");
            lines = lines.subList(0, CODES.length());
        }
        layout = List.copyOf(lines.stream().filter(this::parses).toList());
    }

    /**
     * Broken MiniMessage is caught here, once, instead of every refresh: a bad line would
     * otherwise throw on the timer and flood the console. The line is dropped and named, so
     * the operator learns which one it was.
     */
    private boolean parses(String line) {
        try {
            mini.deserialize(line, PROBE);
            return true;
        } catch (RuntimeException e) {
            plugin.getLogger().warning("hud.lines satırı okunamadı, atlandı: " + line
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
    }

    private void update(Player player) {
        if (!isVisible(player)) {
            clear(player);
            return;
        }

        Scoreboard board = boards.get(player.getUniqueId());
        if (board == null || board.getObjective(OBJECTIVE) == null) {
            board = createBoard();
            boards.put(player.getUniqueId(), board);
        }

        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            return;
        }
        objective.displayName(title());

        TagResolver values = values(player);
        for (int i = 0; i < layout.size(); i++) {
            Team team = board.getTeam("td_l" + i);
            if (team != null) {
                team.prefix(mini.deserialize(layout.get(i), values));
            }
        }

        if (player.getScoreboard() != board) {
            player.setScoreboard(board);
        }
    }

    private void clear(Player player) {
        boolean hadBoard = boards.remove(player.getUniqueId()) != null;
        if (hadBoard || player.getScoreboard().getObjective(OBJECTIVE) != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    private Scoreboard createBoard() {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective =
                board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        // Without this every line carries a red number on the right — the HUD is not a counter.
        objective.numberFormat(NumberFormat.blank());

        for (int i = 0; i < layout.size(); i++) {
            String entry = entry(i);
            Team team = board.registerNewTeam("td_l" + i);
            team.addEntry(entry);
            // Descending scores, so the first configured line ends up at the top.
            objective.getScore(entry).setScore(layout.size() - i);
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
            plugin.getLogger().warning("HUD metni MiniMessage olarak okunamadı, düz metin "
                    + "olarak gösteriliyor: " + raw + " (" + e.getMessage() + ")");
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
