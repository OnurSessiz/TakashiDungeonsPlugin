package com.takashi.dungeons.yaml;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * The plugin's own YAML files as {@link YamlPatch} targets — the one way this plugin writes to
 * them.
 *
 * <h2>Why Bukkit's {@code saveConfig()} is not used</h2>
 * It regenerates the whole file from memory: every flow list ({@code [300, 60, 10]}) is exploded
 * into a block, numbers are re-spelled, and — the worse half — whatever is in memory wins. An
 * operator who edited {@code config.yml} by hand and has not reloaded yet loses that edit the moment
 * a command saves, and nothing tells them. A patch touches the one key it was asked to and reads
 * the file from disk first, so a hand edit elsewhere in the file survives.
 */
public final class DataFiles {

    private DataFiles() {
    }

    public static Path path(Plugin plugin, String name) {
        return new File(plugin.getDataFolder(), name).toPath();
    }

    /**
     * Reads a data-folder file as a patch, unpacking the bundled copy first if it is missing — the
     * same thing each registry does on load.
     */
    public static YamlPatch read(Plugin plugin, String name) throws IOException {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        return YamlPatch.read(file.toPath());
    }

    /**
     * Read, edit, write, in one go.
     *
     * @return {@code null} on success, otherwise the reason — already logged, so a caller only has
     *         to decide what to tell the person who pressed the button
     */
    public static @Nullable String patch(Plugin plugin, String name, Consumer<YamlPatch> edit) {
        try {
            YamlPatch patch = read(plugin, name);
            edit.accept(patch);
            patch.write(path(plugin, name));
            return null;
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not write " + name + ": "
                    + error.getMessage(), error);
            return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
    }
}
