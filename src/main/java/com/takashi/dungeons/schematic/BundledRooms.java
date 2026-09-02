package com.takashi.dungeons.schematic;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Copies the rooms shipped inside the jar out into {@code plugins/TakashiDungeons/schematics/}.
 *
 * <p><b>Why this exists:</b> without it a clean install has ZERO rooms and generation cannot
 * produce anything — the plugin would enable and then refuse to do the one thing it is for.
 * The out-of-box guarantee says a freshly downloaded jar plus FAWE is enough.
 *
 * <p><b>An existing file is never overwritten.</b> The schematics folder is the mapper's
 * workspace: rooms are exported into it from the world, and a room bundled under the same name
 * would otherwise silently eat a newer local export on every restart. Only what is missing gets
 * written, which also means a new version's new rooms arrive on their own while the operator's
 * edits stay. Overwriting is possible, but only when a human asks for it by hand
 * ({@code /tdungeons extract force}).
 *
 * <p><b>Theme folders travel too:</b> the jar path under {@code schematics/} is reproduced as-is,
 * so {@code schematics/crypt/hall.schem} lands in the {@code crypt} theme. Bundling a new theme
 * needs no code change — a folder under {@code src/main/resources/schematics/} is enough.
 *
 * <p>The listing is read from the jar file, the bytes through the plugin's own class loader.
 * Paper hands plugins a remapped copy of their jar; resources are identical in both, but reading
 * through {@link Plugin#getResource(String)} is the path that is guaranteed to serve what the
 * running plugin actually sees.
 */
public final class BundledRooms {

    /** The folder inside the jar that holds the shipped rooms. */
    private static final String PREFIX = "schematics/";

    private final Plugin plugin;
    private final File jar;

    /** @param jar the plugin's own jar — {@code JavaPlugin.getFile()} */
    public BundledRooms(Plugin plugin, File jar) {
        this.plugin = plugin;
        this.jar = jar;
    }

    /** What one extraction run did. */
    public record Result(int written, int skipped, int failed) {
        public int total() {
            return written + skipped + failed;
        }
    }

    /**
     * Writes every bundled room that is missing from {@code target}.
     *
     * @param overwrite replace files that already exist — destructive, ask a human first
     */
    public Result extract(File target, boolean overwrite) {
        List<String> entries = list();
        if (entries.isEmpty()) {
            return new Result(0, 0, 0);
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            plugin.getLogger().warning("Schematic klasörü oluşturulamadı, gömülü odalar "
                    + "çıkarılamadı: " + target.getAbsolutePath());
            return new Result(0, 0, entries.size());
        }

        int written = 0;
        int skipped = 0;
        int failed = 0;
        for (String entry : entries) {
            String relative = entry.substring(PREFIX.length());
            File destination = new File(target, relative);
            if (destination.exists() && !overwrite) {
                skipped++;
                continue;
            }
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                plugin.getLogger().warning("Tema klasörü oluşturulamadı: " + parent.getAbsolutePath());
                failed++;
                continue;
            }
            if (copy(entry, destination)) {
                written++;
            } else {
                failed++;
            }
        }
        return new Result(written, skipped, failed);
    }

    /** The bundled room files, as jar paths. Empty when the plugin runs outside a jar. */
    public List<String> list() {
        if (jar == null || !jar.isFile()) {
            // Running from a classes directory (IDE). Nothing to extract, and not an error.
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> it = file.entries();
            while (it.hasMoreElements()) {
                JarEntry entry = it.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(PREFIX)
                        && entry.getName().length() > PREFIX.length()) {
                    entries.add(entry.getName());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Jar içindeki odalar listelenemedi: " + e.getMessage());
            return List.of();
        }
        entries.sort(String::compareTo);
        return entries;
    }

    private boolean copy(String entry, File destination) {
        try (InputStream in = plugin.getResource(entry)) {
            if (in == null) {
                plugin.getLogger().warning("Gömülü oda okunamadı: " + entry);
                return false;
            }
            try (OutputStream out = Files.newOutputStream(destination.toPath())) {
                in.transferTo(out);
            }
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Gömülü oda yazılamadı: " + entry + " — " + e.getMessage());
            return false;
        }
    }
}
