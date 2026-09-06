package com.takashi.dungeons.text;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every word a player reads.
 *
 * <h2>The rule this class exists to enforce</h2>
 * <b>{@code config.yml} holds behaviour and server-specific values; {@code lang/*.yml} holds every
 * word a player reads.</b> No exceptions — which is why the boss bar title, the gateway's floating
 * label and the HUD layout moved out of {@code config.yml}. A translator who has to hunt through
 * two files for the other half of the sentences translates one of them and ships a plugin that is
 * half in their language.
 *
 * <h2>Why the default ships in English</h2>
 * {@code anahedef.md} §1: the project is given away for <b>visibility</b>, on SpigotMC, Modrinth
 * and Hangar. A Turkish default does not merely inconvenience a Spanish operator — it reads as a
 * Turkish-only plugin on the download page and costs the project the one return it was built for.
 * Turkish ships alongside as {@code lang/tr.yml}; it is one config line away.
 *
 * <h2>Console logs are NOT here, and that is deliberate</h2>
 * Logs are a diagnostic surface, not a user interface. They stay English in the source for two
 * reasons: a bug report quotes a line that has to be greppable against the source, and an operator
 * who cannot read the log cannot even open an issue. Making them translatable would let an
 * operator break their own diagnostics.
 *
 * <h2>Files are extracted, never overwritten</h2>
 * The same rule as {@code mobs.yml} and the bundled rooms. A translator's work has to survive
 * plugin updates, so a file that exists is left exactly as it is.
 *
 * <p>The cost of that rule is a file that goes stale: a plugin update adds a key the operator's
 * copy has never heard of. That is what {@link #fallback} is for — it is read <b>from the jar</b>,
 * not from the extracted {@code en.yml}, so it is guaranteed complete even when the operator has
 * edited or broken the English file on disk. Without it, a translation one version behind shows
 * players a raw key like {@code instance.cleared}.
 */
public final class Messages {

    /** Where the language files live, inside the jar and inside the data folder. */
    public static final String FOLDER = "lang";

    /** The language every missing key falls back to. Its file in the jar is the safety net. */
    public static final String FALLBACK = "en";

    /**
     * Languages shipped inside the jar.
     *
     * <p>Adding one is a file plus an entry here — no code changes anywhere else. A directory
     * listing is not used because a jar is not a directory and walking one at runtime costs more
     * than the list is worth.
     */
    public static final List<String> BUNDLED = List.of("en", "tr");

    private final TakashiDungeonsPlugin plugin;
    private final MiniMessage mini = MiniMessage.miniMessage();

    /** The chosen language, as read from disk. */
    private YamlConfiguration active = new YamlConfiguration();

    /** The jar's English file — complete by construction. */
    private YamlConfiguration fallback = new YamlConfiguration();

    private String language = FALLBACK;

    /**
     * Keys already reported as missing.
     *
     * <p>The boss bar asks for its title once a second per instance. Without this, one stale
     * translation would write a warning per second per dungeon and bury everything else in the
     * log.
     */
    private final Set<String> reportedMissing = new LinkedHashSet<>();

    private @Nullable String loadError;

    public Messages(TakashiDungeonsPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Extracts the bundled languages, then reads the configured one.
     *
     * <p>Never throws. A broken language file must not take the plugin down: generation,
     * instances and mobs have nothing to do with words, and a dungeon that runs while saying the
     * wrong thing is better than a server with no dungeons.
     */
    public void load() {
        reportedMissing.clear();
        loadError = null;

        for (String code : BUNDLED) {
            String path = FOLDER + "/" + code + ".yml";
            if (!new File(plugin.getDataFolder(), path).exists()) {
                plugin.saveResource(path, false);
            }
        }

        fallback = fromJar(FALLBACK);
        language = plugin.getConfig().getString("language", FALLBACK);

        File file = new File(plugin.getDataFolder(), FOLDER + "/" + language + ".yml");
        if (!file.exists()) {
            loadError = "Language '" + language + "' has no file at " + FOLDER + "/" + language
                    + ".yml - falling back to " + FALLBACK + ".";
            plugin.getLogger().warning(loadError);
            active = fallback;
            language = FALLBACK;
            return;
        }
        active = YamlConfiguration.loadConfiguration(file);
        plugin.getLogger().info("Language: " + language + " (" + FOLDER + "/" + language + ".yml)");
    }

    /** Reads a language straight out of the jar, bypassing whatever is on disk. */
    private YamlConfiguration fromJar(String code) {
        try (InputStream stream = plugin.getResource(FOLDER + "/" + code + ".yml")) {
            if (stream == null) {
                plugin.getLogger().severe("Bundled " + FOLDER + "/" + code
                        + ".yml is missing from the jar - messages will show as raw keys.");
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (Exception error) {
            plugin.getLogger().severe("Could not read bundled " + FOLDER + "/" + code + ".yml: "
                    + error);
            return new YamlConfiguration();
        }
    }

    /** The language actually in use — the configured one, or {@code en} if it was not found. */
    public String language() {
        return language;
    }

    /** Why the configured language could not be used, or {@code null}. */
    public @Nullable String loadError() {
        return loadError;
    }

    /**
     * One message, rendered.
     *
     * @param key        dotted path into the language file
     * @param resolvers  MiniMessage tags to fill in. The convention is the one the boss bar
     *                   already taught: values arrive as {@code <time>}, {@code <boss>},
     *                   {@code <reason>} and are inserted <b>unparsed</b>, so a mob name
     *                   containing a tag can never become markup
     */
    public Component get(String key, TagResolver... resolvers) {
        String raw = raw(key);
        try {
            return mini.deserialize(raw, resolvers);
        } catch (RuntimeException error) {
            // A broken tag in a translated file must not throw at a player. The text is shown
            // as written, which is ugly and immediately diagnosable - unlike a swallowed message.
            warnOnce(key, "Message '" + key + "' has broken MiniMessage: " + error.getMessage());
            return Component.text(raw);
        }
    }

    /** The unrendered string, for the few places that need plain text. */
    public String raw(String key) {
        String value = active.getString(key);
        if (value != null) {
            return value;
        }
        String backup = fallback.getString(key);
        if (backup != null) {
            warnOnce(key, "Message '" + key + "' is missing from " + FOLDER + "/" + language
                    + ".yml - using the bundled English text. Delete the file and restart to "
                    + "regenerate it, or copy the key across.");
            return backup;
        }
        warnOnce(key, "Message '" + key + "' exists in no language file - showing the key.");
        return key;
    }

    /**
     * A list-valued entry — the HUD layout is the only one today.
     *
     * <p>Falls back as a whole rather than per element: half a sidebar in one language and half
     * in another is worse than an untranslated one.
     */
    public List<String> list(String key) {
        List<String> value = active.getStringList(key);
        if (!value.isEmpty()) {
            return List.copyOf(value);
        }
        List<String> backup = fallback.getStringList(key);
        if (!backup.isEmpty()) {
            warnOnce(key, "Message list '" + key + "' is missing from " + FOLDER + "/" + language
                    + ".yml - using the bundled English list.");
        }
        return List.copyOf(backup);
    }

    private void warnOnce(String key, String message) {
        if (reportedMissing.add(key)) {
            plugin.getLogger().warning(message);
        }
    }
}
