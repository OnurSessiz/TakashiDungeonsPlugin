package com.takashi.dungeons.gui;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * "Type the value in chat" — the text input a chest window does not have.
 *
 * <h2>Why chat and not an anvil</h2>
 * An anvil's rename field is the other common trick. On 1.21 it means building an anvil view by
 * hand, the text arrives as a rename event on every keystroke, and a player whose client has a
 * different anvil layout (Bedrock through Geyser) cannot use it at all. Chat works on every client,
 * takes MiniMessage as typed, and has room for a long value.
 *
 * <h2>What happens to the line</h2>
 * The window closes, the question is sent, and the next line the player types is <b>captured</b> —
 * the chat event is cancelled, so an operator typing a mob's new name does not broadcast it to the
 * server. {@code cancel} goes back without changing anything, a value the editor rejects is answered
 * with the reason and asked again, and the prompt lapses after {@link #TIMEOUT_SECONDS} or on quit.
 * Either way the window the question came from is opened again.
 */
public final class ChatPrompt implements Listener {

    public static final int TIMEOUT_SECONDS = 90;

    /**
     * @param answer returns {@code null} when the value was taken, or the reason it was not
     */
    private record Pending(Function<String, @Nullable String> answer, Menu reopen, BukkitTask timeout) {
    }

    private final TakashiDungeonsPlugin plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public ChatPrompt(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Asks a question and closes the window.
     *
     * @param question what to type, as MiniMessage lines
     * @param answer   applied on the main thread; returns {@code null} to accept, or the reason to
     *                 refuse (the player is asked again)
     * @param reopen   the menu to show again afterwards
     */
    public void ask(Player player, Menu reopen, Function<String, @Nullable String> answer,
                    String... question) {
        cancel(player.getUniqueId());
        BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(player.getUniqueId()) != null && player.isOnline()) {
                player.sendMessage(Component.text("Input timed out - nothing was changed.",
                        NamedTextColor.GRAY));
            }
        }, TIMEOUT_SECONDS * 20L);
        pending.put(player.getUniqueId(), new Pending(answer, reopen, timeout));

        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            player.sendMessage(Component.empty());
            for (String line : question) {
                player.sendMessage(Icons.line(line));
            }
            player.sendMessage(Icons.line("<gray>Type it in chat - it is not broadcast. "
                    + "<white>cancel</white> to go back."));
        });
    }

    public boolean isWaiting(Player player) {
        return pending.containsKey(player.getUniqueId());
    }

    /**
     * Captures the line. Modern event, lowest priority, and it runs even if something cancelled
     * the chat already: a muted operator still has to be able to type a number into their editor.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!pending.containsKey(player.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
        Bukkit.getScheduler().runTask(plugin, () -> handle(player, text));
    }

    /**
     * The legacy event is cancelled too. Paper fires it first when any plugin still listens to it,
     * and a chat plugin that broadcasts from inside the legacy event would otherwise print the
     * captured line before the modern event gets a say.
     */
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyChat(AsyncPlayerChatEvent event) {
        if (pending.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }

    private void handle(Player player, String text) {
        Pending waiting = pending.get(player.getUniqueId());
        if (waiting == null || !player.isOnline()) {
            return;   // timed out between the chat thread and this tick
        }
        if (text.toLowerCase(Locale.ROOT).equals("cancel")) {
            finish(player, waiting);
            player.sendMessage(Component.text("Cancelled - nothing was changed.", NamedTextColor.GRAY));
            return;
        }
        String problem;
        try {
            problem = waiting.answer().apply(text);
        } catch (RuntimeException error) {
            problem = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        if (problem != null) {
            player.sendMessage(Component.text(problem, NamedTextColor.RED));
            player.sendMessage(Icons.line("<gray>Try again, or <white>cancel</white>."));
            return;
        }
        finish(player, waiting);
    }

    private void finish(Player player, Pending waiting) {
        pending.remove(player.getUniqueId());
        waiting.timeout().cancel();
        waiting.reopen().open();
    }

    private void cancel(UUID player) {
        Pending old = pending.remove(player);
        if (old != null) {
            old.timeout().cancel();
        }
    }

    /** Drops every open question — on disable. */
    public void clear() {
        pending.values().forEach(waiting -> waiting.timeout().cancel());
        pending.clear();
    }
}
