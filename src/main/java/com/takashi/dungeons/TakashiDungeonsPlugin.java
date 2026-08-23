package com.takashi.dungeons;

import com.takashi.dungeons.command.DungeonsCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plugin giriş noktası.
 *
 * <p>FAZ 0 kapsamı: enable/disable yaşam döngüsü ve opsiyonel entegrasyonların tespiti.
 * Entegrasyon tespiti burada duruyor çünkü FAZ 3'teki {@code MobProvider} seçimi ve
 * FAZ 1'deki schematic paste yolu tam olarak bu tabloya bakarak karar verecek.
 */
public final class TakashiDungeonsPlugin extends JavaPlugin {

    /** Softdepend olarak tanımlı, varlığı zorunlu OLMAYAN plugin'ler. */
    private static final List<String> SOFT_INTEGRATIONS =
            List.of("WorldEdit", "FastAsyncWorldEdit", "MythicMobs", "Vault");

    private final Map<String, Boolean> integrations = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        detectIntegrations();

        getLogger().info("TakashiDungeons v" + getPluginMeta().getVersion() + " etkinleştirildi.");
        getLogger().info("Entegrasyonlar:");
        integrations.forEach((name, present) ->
                getLogger().info("  - " + name + ": " + (present ? "bulundu" : "yok")));

        PluginCommand command = getCommand("tdungeons");
        if (command == null) {
            // plugin.yml ile kod arasındaki uyumsuzluğu sessizce geçme
            getLogger().severe("'tdungeons' komutu plugin.yml'de tanımlı değil — komut kaydedilemedi.");
            return;
        }
        DungeonsCommand executor = new DungeonsCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    @Override
    public void onDisable() {
        getLogger().info("TakashiDungeons devre dışı bırakıldı.");
    }

    private void detectIntegrations() {
        integrations.clear();
        for (String name : SOFT_INTEGRATIONS) {
            integrations.put(name, getServer().getPluginManager().isPluginEnabled(name));
        }
    }

    /** Enable sırasında tespit edilen opsiyonel entegrasyonlar (plugin adı → kurulu mu). */
    public Map<String, Boolean> getIntegrations() {
        return Collections.unmodifiableMap(integrations);
    }

    /** Belirtilen opsiyonel entegrasyon enable sırasında bulunduysa {@code true}. */
    public boolean hasIntegration(String pluginName) {
        return integrations.getOrDefault(pluginName, false);
    }
}
