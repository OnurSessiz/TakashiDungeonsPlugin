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
 *   <li>A {@code .schem} file under {@code plugins/TakashiDungeons/schematics/} is read</li>
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

    /** The extension-less names of the schematic files in the folder. */
    public List<String> list() {
        File[] files = directory.listFiles(f -> f.isFile() && hasKnownExtension(f.getName()));
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files).map(f -> stripExtension(f.getName())).sorted().toList();
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
            throw new IOException("Schematic not found: " + key + " (" + directory.getName() + "/)");
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
        for (String ext : EXTENSIONS) {
            File candidate = new File(directory, key + ext);
            if (candidate.isFile()) {
                return candidate;
            }
        }
        // case-insensitive fallback
        File[] files = directory.listFiles(f -> f.isFile() && hasKnownExtension(f.getName()));
        if (files != null) {
            for (File f : files) {
                if (stripExtension(f.getName()).equalsIgnoreCase(key)) {
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
