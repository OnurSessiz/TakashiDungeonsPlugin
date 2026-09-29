package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.yaml.DataFiles;
import com.takashi.dungeons.yaml.YamlPatch;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The one save path every editor goes through. The order is the design:
 *
 * <ol>
 *   <li><b>Permission</b>, again — the window may have been open for ten minutes.</li>
 *   <li><b>Read the file from disk</b>, not from memory: the save lands on what is there now.</li>
 *   <li><b>Conflicts</b> — a touched field that changed on disk since the editor opened refuses
 *       the save by name ({@link Draft}).</li>
 *   <li><b>Apply in memory and validate with the loader's own parser.</b> A result the plugin
 *       would refuse to load never reaches the disk; the GUI cannot produce a file YAML would
 *       reject.</li>
 *   <li><b>Write</b> atomically, then <b>reload</b> the registry so the change is live.</li>
 *   <li><b>Log</b> who changed what. A file edited through a GUI has no author in its history
 *       otherwise, and "who set the boss's health to 4" is a question somebody will ask.</li>
 * </ol>
 */
final class Saver {

    private Saver() {
    }

    /**
     * @param validate given the patched file loaded as a configuration, returns the reason it would
     *                 not load, or {@code null}
     * @return whether it was saved
     */
    static boolean save(TakashiDungeonsPlugin plugin, Player player, String file, Draft draft,
                        Function<YamlConfiguration, @Nullable String> validate, Runnable reload) {
        return save(plugin, player, file, draft, patch -> { }, validate, reload);
    }

    /**
     * @param touchUp runs on the patched text before validation — for the inline comments that
     *                restate a value and would otherwise be left describing the old one
     */
    static boolean save(TakashiDungeonsPlugin plugin, Player player, String file, Draft draft,
                        Consumer<YamlPatch> touchUp,
                        Function<YamlConfiguration, @Nullable String> validate, Runnable reload) {
        if (!player.hasPermission(EditorHub.PERMISSION)) {
            player.sendMessage(Component.text("You do not have " + EditorHub.PERMISSION + ".",
                    NamedTextColor.RED));
            return false;
        }
        if (!draft.dirty()) {
            player.sendMessage(Component.text("Nothing to save.", NamedTextColor.GRAY));
            return false;
        }
        YamlPatch disk;
        try {
            disk = DataFiles.read(plugin, file);
        } catch (IOException | RuntimeException error) {
            player.sendMessage(Component.text("Could not read " + file + ": " + error.getMessage()
                    + " - nothing was saved.", NamedTextColor.RED));
            return false;
        }

        var conflicts = draft.conflicts(disk);
        if (!conflicts.isEmpty()) {
            player.sendMessage(Component.text(file + " was changed on disk since this editor opened ("
                    + String.join(", ", conflicts) + "). Nothing was saved - use "
                    + "'Reload from disk' to see the current values.", NamedTextColor.RED));
            return false;
        }

        try {
            draft.applyTo(disk);
            touchUp.accept(disk);
        } catch (RuntimeException error) {
            player.sendMessage(Component.text("Could not apply the change: " + error.getMessage()
                    + " - nothing was saved.", NamedTextColor.RED));
            return false;
        }

        YamlConfiguration loaded = new YamlConfiguration();
        String problem;
        try {
            loaded.loadFromString(disk.text());
            problem = validate.apply(loaded);
        } catch (InvalidConfigurationException error) {
            problem = "the file would not parse: " + error.getMessage();
        } catch (RuntimeException error) {
            problem = error.getMessage();
        }
        if (problem != null) {
            player.sendMessage(Component.text("Not saved - the result would not load: " + problem,
                    NamedTextColor.RED));
            return false;
        }

        try {
            disk.write(DataFiles.path(plugin, file));
        } catch (IOException error) {
            player.sendMessage(Component.text("Could not write " + file + ": " + error.getMessage(),
                    NamedTextColor.RED));
            return false;
        }

        String summary = draft.summary();
        draft.commit();
        plugin.getLogger().info("[editor] " + player.getName() + " saved " + file + ": " + summary);
        reload.run();
        player.sendMessage(Component.text("Saved to " + file + ": " + summary, NamedTextColor.GREEN));
        return true;
    }
}
