package com.takashi.dungeons.editor;

import com.takashi.dungeons.TakashiDungeonsPlugin;
import com.takashi.dungeons.yaml.DataFiles;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the entries of one section straight out of the file.
 *
 * <p>From the FILE, not from the registry: a registry holds what could be loaded, and the editor has
 * to show what is written — an entry switched off with {@code enabled: false}, or disabled because
 * its provider is missing, is not in the registry, and the editor is exactly where it gets turned
 * back on.
 */
final class Entries {

    /** Id → its fields, in file order; {@link #problem} when the file could not be read. */
    record Read(Map<String, Map<String, Object>> entries, @Nullable String problem) {
    }

    private Entries() {
    }

    static Read read(TakashiDungeonsPlugin plugin, String file, String section) {
        Object raw;
        try {
            raw = DataFiles.read(plugin, file).get(section);
        } catch (IOException | RuntimeException error) {
            return new Read(Map.of(), file + " could not be read: " + error.getMessage());
        }
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Map<String, Object> fields = new LinkedHashMap<>();
                if (entry.getValue() instanceof Map<?, ?> values) {
                    values.forEach((key, value) -> fields.put(String.valueOf(key), value));
                }
                entries.put(String.valueOf(entry.getKey()), fields);
            }
        }
        return new Read(entries, null);
    }
}
