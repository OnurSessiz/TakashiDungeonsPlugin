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
 * Schematic dosyalarını yükler, önbelleğe alır ve dungeon dünyasına paste eder.
 *
 * <p><b>Nasıl çalışır:</b>
 * <ol>
 *   <li>{@code plugins/TakashiDungeons/schematics/} altındaki {@code .schem} dosyası okunur</li>
 *   <li>Okuma <b>her zaman async</b> yapılır (disk I/O + NBT parse main thread'i bloklar)</li>
 *   <li>Elde edilen {@link Clipboard} bellekte cache'lenir - aynı oda yüzlerce kez paste
 *       edileceği için dosya bir kez okunur</li>
 *   <li>Paste, {@link #isAsyncPaste()} durumuna göre async ya da main thread'de çalışır</li>
 * </ol>
 *
 * <p><b>Thread kararı - kritik:</b> FAWE'nin edit session'ı thread-safe ve batch'li yazar,
 * paste async yapılabilir. Düz WorldEdit'te bu <i>doğru değildir</i>; async yazmak konsol
 * hatası ve dünya bozulması üretir. Bu yüzden FAWE varsa async, yoksa main thread kullanılıyor.
 * Her iki durumda da <b>dosya okuma</b> async kalıyor - asıl pahalı kısım o.
 *
 * <p><b>Rotation:</b> WorldEdit'in {@code //rotate} komutuyla aynı yönü versin diye
 * {@code rotateY(-derece)} uygulanıyor (WorldEdit'in kendi ClipboardCommands'ı da böyle yapar).
 * Bu sayede haritacının editörde gördüğü yön ile plugin'in ürettiği yön birebir aynı olur.
 *
 * <p><b>Entity kopyalama kapalı:</b> Oda içindeki mob'lar FAZ 3'te bizim sistemimizle spawn
 * edilecek. Schematic'e gömülü entity'ler her paste'te çoğalır ve stat sistemimizin dışında kalırdı.
 */
public final class SchematicService {

    /** Desteklenen uzantılar - Sponge {@code .schem} birincil, eski {@code .schematic} tolere edilir. */
    private static final List<String> EXTENSIONS = List.of(".schem", ".schematic");

    private final Plugin plugin;
    private final File directory;
    private final boolean asyncPaste;

    /** Ad (uzantısız, küçük harf) -> yüklenmiş clipboard. */
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

    /** Paste'in async çalışıp çalışmadığı (FAWE varsa true). */
    public boolean isAsyncPaste() {
        return asyncPaste;
    }

    /** Klasördeki schematic dosyalarının uzantısız adları. */
    public List<String> list() {
        File[] files = directory.listFiles(f -> f.isFile() && hasKnownExtension(f.getName()));
        if (files == null) {
            return List.of();
        }
        return Arrays.stream(files).map(f -> stripExtension(f.getName())).sorted().toList();
    }

    /** Cache'i boşaltır - dosyalar diskte değiştiyse (reload) çağrılır. */
    public void invalidateCache() {
        cache.clear();
    }

    /**
     * Schematic'i yükler; aynı ad için ikinci çağrı diskten değil cache'ten döner.
     *
     * @param name uzantısız dosya adı
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
                // Başarısız yükleme cache'te kalmasın: dosya düzeltilince yeniden denenebilsin.
                // İki argümanlı remove: bu arada başka bir thread yeni bir future koyduysa onu silme.
                cache.remove(key, future);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private Clipboard readBlocking(String key) throws IOException {
        File file = resolve(key);
        if (file == null) {
            throw new IOException("Schematic bulunamadı: " + key + " (" + directory.getName() + "/)");
        }
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IOException("Tanınmayan schematic formatı: " + file.getName());
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
        // büyük/küçük harf toleransı
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
     * Clipboard'ı verilen konuma paste eder.
     *
     * @param rotation 0/90/180/270 - başka değer {@link IllegalArgumentException} atar
     * @return paste süresi (ms)
     */
    public CompletableFuture<Long> paste(Clipboard clipboard, World world,
                                         int x, int y, int z, int rotation, boolean ignoreAir) {
        if (rotation % 90 != 0) {
            throw new IllegalArgumentException("Rotation 90'in katı olmalı: " + rotation);
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
            // WorldEdit'in //rotate komutuyla aynı yön: negatif açı
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
            throw new IllegalStateException("Paste başarısız: " + e.getMessage(), e);
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
