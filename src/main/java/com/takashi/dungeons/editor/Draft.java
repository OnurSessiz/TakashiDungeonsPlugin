package com.takashi.dungeons.editor;

import com.takashi.dungeons.yaml.YamlPatch;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The unsaved state of one entry: what the file said when the editor opened, and what has been
 * changed since.
 *
 * <h2>Only the changed fields are written</h2>
 * A save does not write the entry back — it writes the fields that were touched, onto the file as
 * it is on disk <b>at that moment</b>. A hand edit to a field the editor never touched survives.
 *
 * <h2>Optimistic lock</h2>
 * If the operator (or a second editor) changed one of the touched fields on disk since this draft
 * was opened, {@link #conflicts} names it and the save is refused. Overwriting quietly would lose a
 * correction somebody made on purpose; refusing costs one "reload from disk" click.
 *
 * <p>A field set back to its original value is no longer a change — a draft that has been nudged
 * up and back down again is clean.
 */
public final class Draft {

    private final List<String> base;
    private final Map<String, @Nullable Object> original = new HashMap<>();
    private final Map<String, @Nullable Object> changes = new LinkedHashMap<>();

    /**
     * @param base path to the entry's section, e.g. {@code [mobs, crypt_zombie]}
     */
    public Draft(List<String> base, Map<String, ?> values) {
        this.base = List.copyOf(base);
        original.putAll(values);
    }

    public @Nullable Object get(String field) {
        return changes.containsKey(field) ? changes.get(field) : original.get(field);
    }

    public @Nullable Object original(String field) {
        return original.get(field);
    }

    /** {@code null} means "remove the key". */
    public void set(String field, @Nullable Object value) {
        if (Objects.equals(Num.canonical(value), Num.canonical(original.get(field)))) {
            changes.remove(field);
        } else {
            changes.put(field, value);
        }
    }

    public boolean isChanged(String field) {
        return changes.containsKey(field);
    }

    public boolean dirty() {
        return !changes.isEmpty();
    }

    public void discard() {
        changes.clear();
    }

    /** Changed fields whose value on disk is no longer what this draft started from. */
    public List<String> conflicts(YamlPatch disk) {
        List<String> conflicts = new ArrayList<>();
        for (String field : changes.keySet()) {
            Object now = disk.get(path(field));
            if (!Objects.equals(Num.canonical(now), Num.canonical(original.get(field)))) {
                conflicts.add(field);
            }
        }
        return conflicts;
    }

    public void applyTo(YamlPatch patch) {
        for (Map.Entry<String, @Nullable Object> change : changes.entrySet()) {
            if (change.getValue() == null) {
                patch.remove(path(change.getKey()));
            } else {
                patch.set(path(change.getKey()), change.getValue());
            }
        }
    }

    /** After a successful save: what was written is the new starting point. */
    public void commit() {
        original.putAll(changes);
        changes.clear();
    }

    /** {@code weight 150 → 175, health [16, 22] → [18, 24]} */
    public String summary() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, @Nullable Object> change : changes.entrySet()) {
            parts.add(change.getKey() + " " + Num.describe(original.get(change.getKey())) + " → "
                    + Num.describe(change.getValue()));
        }
        return String.join(", ", parts);
    }

    private List<String> path(String field) {
        List<String> path = new ArrayList<>(base);
        path.addAll(YamlPatch.path(field));
        return path;
    }
}
