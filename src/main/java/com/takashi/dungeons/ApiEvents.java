package com.takashi.dungeons;

import com.takashi.dungeons.api.event.DungeonCloseEvent;
import com.takashi.dungeons.api.event.DungeonCompleteEvent;
import com.takashi.dungeons.api.event.DungeonCreateEvent;
import com.takashi.dungeons.api.event.DungeonEnterEvent;
import com.takashi.dungeons.api.event.DungeonEvent;
import com.takashi.dungeons.api.event.DungeonLeaveEvent;
import com.takashi.dungeons.api.event.DungeonMobKillEvent;
import com.takashi.dungeons.instance.InstanceManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;

/**
 * The one place the plugin's internal signals become Bukkit events.
 *
 * <h2>Why a bridge and not events raised where things happen</h2>
 * The mob layer publishes "a mob died"; the instance layer publishes "a player went in". Neither
 * knows that an API exists, and that is what has kept them re-arrangeable through seven phases.
 * Raising events from inside them would put a public promise in the middle of private code — the
 * next refactor would be a breaking change without anybody deciding to make one. Here, every
 * promise is in one file that does nothing else.
 *
 * <h2>This is also the translation layer</h2>
 * The internal signals carry internal objects: {@code MobKill} holds a {@code MobDefinition}, the
 * clear signal holds the whole kill. What crosses into an event is a narrowed version — the mob's
 * id as a string, not the definition. An addon cannot depend on what it is never handed.
 *
 * <p><b>Public only because {@code /tdungeons api} lives in another package.</b> It is not part of
 * the API and carries no promise — that is the {@code api} package's job, and the folder is the
 * line.
 */
public final class ApiEvents {

    private final TakashiDungeonsPlugin plugin;

    /** The debug listener, while {@code /tdungeons api debug on} is in force. */
    private DebugListener debug;

    public ApiEvents(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Subscribes to every internal signal.
     *
     * <p>Registered last of everything, so an API listener sees a world the plugin has already
     * finished reacting to: the stats are counted, the merchant is gone, the slot is released.
     */
    public void register() {
        InstanceManager instances = plugin.getInstanceManager();

        instances.onCreated(dungeon -> fire(new DungeonCreateEvent(dungeon)));

        // The only signal that can answer back. A listener cancelling the event is the addon
        // saying no; anything else that goes wrong is not.
        instances.entryGuard((dungeon, player) -> {
            DungeonEnterEvent event = new DungeonEnterEvent(dungeon, player);
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled();
        });

        instances.onExit((dungeon, uuid) -> {
            // Usually null: a disconnect is the commonest way out of a dungeon there is.
            Player player = Bukkit.getPlayer(uuid);
            fire(new DungeonLeaveEvent(dungeon, uuid, player));
        });

        instances.onCleared((dungeon, kill) -> fire(new DungeonCompleteEvent(
                dungeon,
                // Read now, while everyone is still registered: after the grace period the room
                // empties and the question "who cleared this" has no answer left.
                dungeon.players(),
                kill.killer(),
                kill.definition() == null ? null : kill.definition().id())));

        instances.onClosed(dungeon -> fire(new DungeonCloseEvent(dungeon)));

        plugin.getMobService().onKill(kill -> fire(new DungeonMobKillEvent(
                kill.instance(),
                kill.entity(),
                kill.killer(),
                kill.boss(),
                kill.definition() == null ? null : kill.definition().id())));
    }

    /**
     * Raises an event, from the main thread whatever thread we are on.
     *
     * <p>Every signal bridged here is raised from a main-thread moment today, so the hop never
     * happens. It is here because the day one of them moves — a close that finishes on the
     * WorldEdit thread, say — the alternative is Bukkit throwing {@code IllegalStateException} at
     * a listener that did nothing wrong. A promise that only holds while the internals stay
     * arranged the way they are today is not a promise.
     */
    private void fire(Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(event));
    }

    // ------------------------------------------------------------------ the debug listener

    /** Whether every API event is being written to the console right now. */
    public boolean isDebugging() {
        return debug != null;
    }

    /**
     * Turns console logging of every API event on or off.
     *
     * <p>For the question an addon developer cannot otherwise answer: "is the event not firing, or
     * is my listener not registered?" Without this the only way to tell them apart is to write a
     * second plugin. Off by default — it writes a line per mob death.
     *
     * @return the new state
     */
    public boolean debug(boolean on) {
        if (on == isDebugging()) {
            return on;
        }
        if (on) {
            debug = new DebugListener();
            Bukkit.getPluginManager().registerEvents(debug, plugin);
        } else {
            HandlerList.unregisterAll(debug);
            debug = null;
        }
        return on;
    }

    /**
     * Logs every API event.
     *
     * <p>At {@link EventPriority#MONITOR}, which is the honest priority for something that only
     * watches — and it means the enter line reports what the event ENDED UP as, cancellation
     * included, rather than what it started as.
     */
    private final class DebugListener implements Listener {

        @EventHandler(priority = EventPriority.MONITOR)
        public void onCreate(DungeonCreateEvent event) {
            log(event, "theme=" + event.dungeon().theme() + " size=" + event.dungeon().sizeKey()
                    + " rooms=" + event.dungeon().roomCount() + " seed=" + event.dungeon().seed());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnter(DungeonEnterEvent event) {
            log(event, event.player().getName()
                    + (event.isCancelled() ? " REFUSED by a listener" : " entered"));
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onLeave(DungeonLeaveEvent event) {
            log(event, (event.player() == null
                    ? event.playerId() + " (offline)" : event.player().getName()) + " left");
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onComplete(DungeonCompleteEvent event) {
            log(event, "boss=" + event.bossId()
                    + " killer=" + (event.killer() == null ? "none" : event.killer().getName())
                    + " participants=" + event.participants().size());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(DungeonCloseEvent event) {
            log(event, "cleared=" + event.dungeon().isCleared());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onMobKill(DungeonMobKillEvent event) {
            log(event, (event.boss() ? "BOSS " : "") + event.mobId()
                    + " killed by " + (event.killer() == null ? "nobody" : event.killer().getName()));
        }

        private void log(DungeonEvent event, String detail) {
            plugin.getLogger().info("[api] " + event.getEventName()
                    + " dungeon#" + event.dungeon().id() + " - " + detail);
        }
    }
}
