package com.takashi.dungeons.schematic;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads schematic files, caches them, and pastes them into the dungeon world.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>A {@code .schem} file under {@code plugins/TakashiDungeons/schematics/} is read —
 *       from the root for the {@code default} theme, from {@code <theme>/} otherwise</li>
 *   <li>Reading is <b>always async</b> (disk I/O plus NBT parsing would block the main thread)</li>
 *   <li>The resulting {@link Clipboard} is cached in memory — the same room gets pasted
 *       hundreds of times, so the file is read once</li>
 *   <li>The paste itself runs async or on the main thread depending on {@link #isAsyncPaste()}</li>
 * </ol>
 *
 * <p><b>The threading decision — this one matters:</b> FAWE's edit session is thread-safe and
 * writes in batches, so a paste can be async. With plain WorldEdit that is <i>not</i> true;
 * writing async there produces console errors and world corruption. Hence: async when FAWE is
 * present, main thread otherwise. Either way <b>file reading</b> stays async — that is the
 * genuinely expensive part.
 *
 * <p><b>Rotation:</b> {@code rotateY(-degrees)} is applied so the result matches WorldEdit's own
 * {@code //rotate} command (WorldEdit's ClipboardCommands does the same). That way the direction
 * a mapper sees in the editor is exactly the direction the plugin produces.
 *
 * <p><b>Entity copying is off:</b> mobs inside a room are spawned by our own system in phase 3.
 * Entities embedded in a schematic would multiply on every paste and sit outside the stat system.
 */
public final class SchematicService {

    /** Supported extensions — Sponge {@code .schem} is primary, legacy {@code .schematic} tolerated. */
    private static final List<String> EXTENSIONS = List.of(".schem", ".schematic");

    /**
     * The theme a room belongs to when its files sit directly in the schematics root.
     *
     * <p>Keeping the root a real theme is what makes this change need no migration: every
     * schematic that existed before themes did is now a {@code default} room, and every command
     * that referred to it by its bare name still does.
     */
    public static final String DEFAULT_THEME = "default";

    private final Plugin plugin;
    private final File directory;
    private final boolean asyncPaste;

    /** Name (no extension, lower-cased) → loaded clipboard. */
    private final Map<String, CompletableFuture<Clipboard>> cache = new ConcurrentHashMap<>();

    public SchematicService(Plugin plugin, File directory, boolean asyncPaste) {
        this.plugin = plugin;
        this.directory = directory;
        this.asyncPaste = asyncPaste;
        if (!directory.exists() && !directory.mkdirs()) {
            plugin.getLogger().warning("Schematic klasörü oluşturulamadı: " + directory.getAbsolutePath());
        }
    }

    public File getDirectory() {
        return directory;
    }

    /** Whether pasting runs async (true when FAWE is present). */
    public boolean isAsyncPaste() {
        return asyncPaste;
    }

    /**
     * The folder a theme's rooms live in: the schematics root for {@link #DEFAULT_THEME},
     * a subfolder of it otherwise.
     *
     * <p>The lookup is case-insensitive, because theme names arrive from chat and the file
     * system is not the place to enforce spelling.
     */
    public File directoryFor(String theme) {
        if (theme == null || DEFAULT_THEME.equalsIgnoreCase(theme)) {
            return directory;
        }
        File exact = new File(directory, theme);
        if (exact.isDirectory()) {
            return exact;
        }
        File[] subs = directory.listFiles(File::isDirectory);
        if (subs != null) {
            for (File sub : subs) {
                if (sub.getName().equalsIgnoreCase(theme)) {
                    return sub;
                }
            }
        }
        // Doesn't exist; callers report it through the empty listing rather than throwing here.
        return exact;
    }

    /**
     * The themes that actually hold at least one schematic, sorted.
     *
     * <p><b>Themes are discovered, not declared.</b> A folder with rooms in it is a theme; there
     * is no theme list in the config that could fall out of sync with the disk. Same reasoning
     * as {@code generation.md} §9 on door facings: a field you can write is a field you can
     * write wrong.
     */
    public List<String> themes() {
        List<String> found = new ArrayList<>();
        if (!listIn(directory).isEmpty()) {
            found.add(DEFAULT_THEME);
        }
        File[] subs = directory.listFiles(File::isDirectory);
        if (subs != null) {
            Arrays.stream(subs)
                    .filter(sub -> !listIn(sub).isEmpty())
                    .map(File::getName)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(found::add);
        }
        return List.copyOf(found);
    }

    /** Every room key across every theme. */
    public List<String> list() {
        List<String> all = new ArrayList<>();
        for (String theme : themes()) {
            all.addAll(list(theme));
        }
        return List.copyOf(all);
    }

    /** One theme's room keys. */
    public List<String> list(String theme) {
        String resolved = theme == null ? DEFAULT_THEME : theme;
        return listIn(directoryFor(resolved)).stream().map(name -> key(resolved, name)).toList();
    }

    /** The extension-less schematic names directly inside one folder. */
    private static List<String> listIn(File folder) {
        File[] files = folder.listFiles(f -> f.isFile() && hasKnownExtension(f.getName()));
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files).map(f -> stripExtension(f.getName())).sorted().toList();
    }

    /**
     * Builds a room key. The default theme's rooms keep their bare name, so every command that
     * worked before themes existed still works verbatim.
     */
    public static String key(String theme, String name) {
        return theme == null || DEFAULT_THEME.equalsIgnoreCase(theme) ? name : theme + "/" + name;
    }

    /** The theme part of a key; a key without a {@code /} belongs to the default theme. */
    public static String themeOf(String key) {
        int slash = key.indexOf('/');
        return slash < 0 ? DEFAULT_THEME : key.substring(0, slash);
    }

    /** The file-name part of a key. */
    public static String nameOf(String key) {
        int slash = key.indexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    /** Empties the cache — called when the files on disk have changed (reload). */
    public void invalidateCache() {
        cache.clear();
    }

    /**
     * Loads a schematic; a second call for the same name comes from the cache, not the disk.
     *
     * @param name file name without extension
     */
    public CompletableFuture<Clipboard> load(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(key, this::readAsync);
    }

    private CompletableFuture<Clipboard> readAsync(String key) {
        CompletableFuture<Clipboard> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                future.complete(readBlocking(key));
            } catch (Exception e) {
                // Don't leave a failed load in the cache: once the file is fixed it should be
                // retried. The two-argument remove avoids deleting a future another thread
                // installed in the meantime.
                cache.remove(key, future);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private Clipboard readBlocking(String key) throws IOException {
        File file = resolve(key);
        if (file == null) {
            throw new IOException("Schematic not found: " + key + " (theme folder: "
                    + directoryFor(themeOf(key)).getPath() + ")");
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IOException("Unrecognized schematic format: " + file.getName());
        }
        try (InputStream in = new FileInputStream(file);
             ClipboardReader reader = format.getReader(in)) {
            return reader.read();
        }
    }

    private File resolve(String key) {
        File folder = directoryFor(themeOf(key));
        String name = nameOf(key);
        for (String ext : EXTENSIONS) {
            File candidate = new File(folder, name + ext);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        // case-insensitive fallback
        File[] files = folder.listFiles(f -> f.isFile() && hasKnownExtension(f.getName()));
        if (files != null) {
            for (File f : files) {
                if (stripExtension(f.getName()).equalsIgnoreCase(name)) {
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * Pastes the clipboard at the given location.
     *
     * @param rotation 0/90/180/270 — any other value throws {@link IllegalArgumentException}
     * @return how long the paste took, in ms
     */
    public CompletableFuture<Long> paste(Clipboard clipboard, World world,
                                         int x, int y, int z, int rotation, boolean ignoreAir) {
        if (rotation % 90 != 0) {
            throw new IllegalArgumentException("Rotation must be a multiple of 90: " + rotation);
        }
        CompletableFuture<Long> future = new CompletableFuture<>();
        Runnable task = () -> {
            long start = System.nanoTime();
            try {
                pasteBlocking(clipboard, world, x, y, z, rotation, ignoreAir);
                future.complete((System.nanoTime() - start) / 1_000_000L);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (asyncPaste) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
        return future;
    }

    private void pasteBlocking(Clipboard clipboard, World world,
                               int x, int y, int z, int rotation, boolean ignoreAir) {
        ClipboardHolder holder = new ClipboardHolder(clipboard);
        int normalized = Math.floorMod(rotation, 360);
        if (normalized != 0) {
            // Same direction as WorldEdit's //rotate command: a negative angle
            holder.setTransform(new AffineTransform().rotateY(-normalized));
        }

        com.sk89q.worldedit.world.World weWorld = BukkitAdapter.adapt(world);
        try (EditSession session = WorldEdit.getInstance().newEditSessionBuilder()
                .world(weWorld)
                .maxBlocks(-1)
                .build()) {
            Operation operation = holder.createPaste(session)
                    .to(BlockVector3.at(x, y, z))
                    .ignoreAirBlocks(ignoreAir)
                    .copyEntities(false)
                    .copyBiomes(false)
                    .build();
            Operations.complete(operation);
        } catch (Exception e) {
            throw new IllegalStateException("Paste failed: " + e.getMessage(), e);
        }
    }

    private static boolean hasKnownExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
