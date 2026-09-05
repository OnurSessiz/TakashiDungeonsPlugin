package com.takashi.dungeons.mob;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

/**
 * Mobs from MythicMobs — reached entirely through <b>reflection</b>.
 *
 * <h2>Why reflection instead of a Maven dependency</h2>
 * Compiling against MythicMobs would mean adding the Lumine repository to {@code pom.xml}, and
 * from then on the project cannot be built without reaching that host. That is a real cost for
 * <i>two</i> method calls, on an integration that is optional by rule ({@code anahedef.md} §5 —
 * the core must never hard-depend on a mob plugin). The API surface used here is small and stable:
 * spawn one mob by name, list the names.
 *
 * <p>The trade is that a MythicMobs API change becomes a runtime problem rather than a compile
 * error. That is handled the only honest way: every reflective failure disables this provider and
 * <b>says so once, loudly, in the log</b>. It never throws into a spawn loop and never falls back
 * to vanilla behind the operator's back.
 *
 * <h2>Always constructed, even without the plugin</h2>
 * {@link #isAvailable()} answers the question instead. Registering the provider conditionally
 * would make {@code mythicmobs:Foo} report "bilinmeyen provider" on a server that simply has not
 * installed MythicMobs — the wrong diagnosis for the right symptom.
 */
public final class MythicMobsProvider implements MobProvider {

    public static final String ID = "mythicmobs";

    private static final String PLUGIN_NAME = "MythicMobs";
    private static final String ENTRY_CLASS = "io.lumine.mythic.bukkit.MythicBukkit";

    private final Plugin owner;

    /** Resolved on first use and cached; {@code null} until then or after a failure. */
    private @Nullable Method spawnMethod;
    private @Nullable Object apiHelper;

    /** Known mob names, refreshed whenever the registry is reloaded. */
    private List<String> names = List.of();

    /** Set once a reflective call has failed, so the log carries one line and not one per spawn. */
    private boolean broken;

    public MythicMobsProvider(Plugin owner) {
        this.owner = owner;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "MythicMobs";
    }

    @Override
    public boolean isAvailable() {
        return !broken && owner.getServer().getPluginManager().isPluginEnabled(PLUGIN_NAME);
    }

    /**
     * Re-reads the mob name list. Called when {@code mobs.yml} is (re)loaded, so a MythicMobs
     * {@code /mm reload} followed by {@code /tdungeons reload} is enough to pick up a new mob.
     */
    public void refresh() {
        names = List.of();
        if (!isAvailable()) {
            return;
        }
        try {
            Object instance = entryInstance();
            Object mobManager = instance.getClass().getMethod("getMobManager").invoke(instance);
            Object raw = mobManager.getClass().getMethod("getMobNames").invoke(mobManager);
            if (raw instanceof Collection<?> collection) {
                List<String> found = new ArrayList<>(collection.size());
                for (Object entry : collection) {
                    if (entry != null) {
                        found.add(entry.toString());
                    }
                }
                found.sort(String::compareToIgnoreCase);
                names = List.copyOf(found);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail("mob listesi okunamadı", error);
        }
    }

    @Override
    public boolean supports(String mobKey) {
        if (!isAvailable()) {
            return false;
        }
        // The name list is the check. Asking MythicMobs to resolve the name would be more exact,
        // but it is one more reflective surface for no gain: a name that is in the list and fails
        // at spawn time already lands in the same warning path.
        return resolveName(mobKey) != null;
    }

    @Override
    public @Nullable LivingEntity spawn(String mobKey, Location location) {
        if (!isAvailable()) {
            return null;
        }
        String name = resolveName(mobKey);
        try {
            Object helper = apiHelper();
            Method method = spawnMethod(helper);
            Object result = method.invoke(helper, name == null ? mobKey : name, location);
            if (result instanceof LivingEntity living) {
                return living;
            }
            if (result instanceof Entity entity) {
                // A MythicMobs mob backed by a non-living entity (a block display, a projectile
                // skin) cannot carry health or be killed for loot. Removing it is kinder than
                // leaving an indestructible decoration standing in the room.
                entity.remove();
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail("'" + mobKey + "' doğurulamadı", error);
            return null;
        }
    }

    @Override
    public Collection<String> knownKeys() {
        return names;
    }

    /**
     * A MythicMobs mob arrives with the stats its author wrote. Overwriting them by default would
     * delete the reason the plugin is installed — {@code anahedef.md} §4.
     */
    @Override
    public boolean defaultStatOverride() {
        return false;
    }

    /** Matches exactly first, then case-insensitively; {@code null} when the name is unknown. */
    private @Nullable String resolveName(String mobKey) {
        String value = mobKey.trim();
        if (names.contains(value)) {
            return value;
        }
        for (String name : names) {
            if (name.equalsIgnoreCase(value)) {
                return name;
            }
        }
        return null;
    }

    private Object entryInstance() throws ReflectiveOperationException {
        Class<?> entry = Class.forName(ENTRY_CLASS);
        Object instance = entry.getMethod("inst").invoke(null);
        if (instance == null) {
            throw new IllegalStateException(ENTRY_CLASS + ".inst() null döndü");
        }
        return instance;
    }

    private Object apiHelper() throws ReflectiveOperationException {
        if (apiHelper != null) {
            return apiHelper;
        }
        Object instance = entryInstance();
        Object helper = instance.getClass().getMethod("getAPIHelper").invoke(instance);
        if (helper == null) {
            throw new IllegalStateException("MythicBukkit.getAPIHelper() null döndü");
        }
        apiHelper = helper;
        return helper;
    }

    private Method spawnMethod(Object helper) throws ReflectiveOperationException {
        if (spawnMethod != null) {
            return spawnMethod;
        }
        Method method = helper.getClass().getMethod("spawnMythicMob", String.class, Location.class);
        method.setAccessible(true);
        spawnMethod = method;
        return method;
    }

    /**
     * Disables the provider and reports why — once.
     *
     * <p>Disabling rather than retrying is the point: if the API moved, every later call fails the
     * same way, and a warning per spawn attempt would bury the one line that explains it.
     */
    private void fail(String what, Throwable error) {
        if (!broken) {
            broken = true;
            owner.getLogger().log(Level.WARNING, "MythicMobs entegrasyonu devre dışı bırakıldı — "
                    + what + ". MythicMobs sürümü destekleniyor mu kontrol edin; bu sağlayıcıya "
                    + "bağlı mob tanımları artık doğmayacak.", error);
        }
        apiHelper = null;
        spawnMethod = null;
        names = List.of();
    }

    /** Whether a reflective call has failed and taken the provider out of service. */
    public boolean isBroken() {
        return broken;
    }

    /** Clears the broken flag — used by {@code /tdungeons reload} so a fixed setup can recover. */
    public void reset() {
        broken = false;
        apiHelper = null;
        spawnMethod = null;
    }

    @Override
    public String toString() {
        return ID + " (" + (isAvailable() ? names.size() + " mob" : "yok") + ")";
    }
}
