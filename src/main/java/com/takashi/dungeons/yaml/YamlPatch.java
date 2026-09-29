package com.takashi.dungeons.yaml;

import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.CollectionNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Changes values inside a YAML file <b>without rewriting the file</b>.
 *
 * <h2>Why not just save the configuration</h2>
 * Measured on the shipped {@code mobs.yml}, changing one {@code weight}:
 * <ul>
 *   <li>Bukkit's {@code YamlConfiguration.save} keeps the comments (it has since 1.18) but
 *       regenerates everything else — every {@code [16, 22]} becomes four lines and {@code 0.80}
 *       becomes {@code 0.8}. A <b>237-line</b> diff for one number.</li>
 *   <li>Serialising SnakeYAML's own node tree keeps comments and flow style, and still touches 88
 *       lines: indentation of lists, spaces on blank lines, {@code { a: 1 }} → {@code {a: 1}}.</li>
 * </ul>
 * These files are the operator's documentation. An editor that changes one weight has to produce a
 * one-line diff, or it reads as "the GUI mangled my file" — and it would be right.
 *
 * <h2>How: splice the text, use the parser only to find where</h2>
 * SnakeYAML composes the file into nodes, and every node knows where it starts and ends in the
 * text. A {@link #set} replaces exactly that character range; everything outside it — comments,
 * alignment, line endings, the quote style of the neighbours — is left byte for byte as it was.
 *
 * <p>After every operation the result is parsed again. If it does not parse, the operation is
 * undone and throws: this class cannot leave behind a file the plugin would fail to read.
 *
 * <p>No Bukkit import on purpose — the geo-probe exercises it without a server.
 */
public final class YamlPatch {

    private static final char BOM = '﻿';

    private String text;
    private final boolean bom;
    private final String newline;

    private YamlPatch(String raw) {
        this.bom = !raw.isEmpty() && raw.charAt(0) == BOM;
        this.text = bom ? raw.substring(1) : raw;
        // The file's own line ending is kept: a Windows operator's CRLF file that comes back with
        // one LF line in it shows up in every diff tool as a whole-file change.
        this.newline = text.contains("\r\n") ? "\r\n" : "\n";
        compose(text);   // refuse to start from something that does not parse
    }

    /** Wraps YAML text. Throws {@link IllegalArgumentException} when it does not parse. */
    public static YamlPatch of(String text) {
        return new YamlPatch(text);
    }

    /** Reads a UTF-8 file. Throws {@link IllegalArgumentException} when it does not parse. */
    public static YamlPatch read(Path file) throws IOException {
        return new YamlPatch(Files.readString(file, StandardCharsets.UTF_8));
    }

    /** The current text, without a byte-order mark even if the file had one. */
    public String text() {
        return text;
    }

    /**
     * Writes the text back, atomically where the file system allows it.
     *
     * <p>A temporary file next to the target and a move: a server that dies halfway through a
     * save must leave the old file or the new one, never half of each.
     */
    public void write(Path file) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, bom ? BOM + text : text, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ reading

    /** Splits {@code a.b.c}. Keys in the files this plugin edits never contain a dot. */
    public static List<String> path(String dotted) {
        return Arrays.asList(dotted.split("\\."));
    }

    public boolean contains(String path) {
        return get(path) != null;
    }

    /**
     * The value at a path as the YAML loader constructs it ({@code Integer}, {@code Double},
     * {@code String}, {@code List}, {@code Map}…), or {@code null} when absent.
     */
    public @Nullable Object get(String path) {
        return get(path(path));
    }

    public @Nullable Object get(List<String> path) {
        Object current = new Yaml(new LoaderOptions()).load(text);
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    // ------------------------------------------------------------------ writing

    public YamlPatch set(String path, Object value) {
        return set(path(path), value);
    }

    /**
     * Puts {@code value} at {@code path}: replaces the value in place when the key exists, adds the
     * key (and any missing parents) at the end of the section when it does not.
     *
     * <p>Values: {@code Boolean}, any {@code Number}, {@code String}, or a {@code List} of those —
     * written in flow style, {@code [16, 22]}, the way the shipped files write ranges. Sections are
     * not written as values; they appear when a path needs them.
     */
    public YamlPatch set(List<String> path, Object value) {
        if (path.isEmpty()) {
            throw new IllegalArgumentException("empty path");
        }
        String before = text;
        try {
            apply(path, value);
            compose(text);
        } catch (RuntimeException error) {
            text = before;
            throw error;
        }
        return this;
    }

    public boolean remove(String path) {
        return remove(path(path));
    }

    /**
     * Deletes a key together with its value — every line from the key to the end of the value.
     *
     * <p>Comment lines ABOVE the key are kept, and so end up above the next key. That is the
     * conservative choice: a comment is often a section header ({@code # ---- weak}) that happens to
     * sit above the first entry, and deleting the first entry must not delete the header.
     *
     * @return {@code false} when there was nothing to remove
     */
    public boolean remove(List<String> path) {
        String before = text;
        try {
            Located found = locate(path);
            if (found == null) {
                return false;
            }
            if (found.parent().getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
                throw new UnsupportedOperationException(String.join(".", path)
                        + ": removing a key from a flow mapping ({ ... }) is not supported");
            }
            int keyAt = index(found.tuple().getKeyNode().getStartMark());
            int from = lineStart(keyAt);
            if (!text.substring(from, keyAt).isBlank()) {
                // "- key: value" — the line also carries a list marker (or another key), and
                // deleting it whole would delete more than this key.
                throw new UnsupportedOperationException(String.join(".", path)
                        + ": the key does not start its own line");
            }
            int to = lineEndInclusive(end(found.tuple().getValueNode()));
            text = text.substring(0, from) + text.substring(to);
            compose(text);
            return true;
        } catch (RuntimeException error) {
            text = before;
            throw error;
        }
    }

    // ------------------------------------------------------------------ inline comments

    /**
     * The comment after a value on its own line ({@code common: 625   # 62.5%} → {@code 62.5%}), or
     * {@code null} when there is none.
     */
    public @Nullable String comment(String path) {
        Located found = locate(path(path));
        if (found == null) {
            return null;
        }
        int[] span = commentSpan(found.tuple().getValueNode());
        return span == null ? null : text.substring(span[0], span[1]).strip();
    }

    /**
     * Rewrites the text of an EXISTING inline comment; the spacing before the {@code #} is kept, so
     * a column of aligned comments stays aligned. A value without a comment is left alone — this
     * never adds one.
     *
     * <p>Why it exists: an inline comment that restates the value ({@code # 62.5%}) is exactly the
     * kind that starts lying the moment an editor changes the number and leaves the comment.
     *
     * @return whether there was a comment to rewrite
     */
    public boolean comment(String path, String comment) {
        Located found = locate(path(path));
        if (found == null) {
            return false;
        }
        int[] span = commentSpan(found.tuple().getValueNode());
        if (span == null) {
            return false;
        }
        String before = text;
        text = text.substring(0, span[0]) + " " + comment.strip() + text.substring(span[1]);
        try {
            compose(text);
        } catch (RuntimeException error) {
            text = before;
            throw error;
        }
        return true;
    }

    /** Start (just past the {@code #}) and end of the comment on a value's last line. */
    private int @Nullable [] commentSpan(Node value) {
        int valueEnd = end(value);
        int lineEnd = lineEndExclusive(valueEnd);
        String rest = text.substring(valueEnd, lineEnd);
        int hash = rest.indexOf('#');
        if (hash <= 0 || !rest.substring(0, hash).isBlank()) {
            // A comment has to be separated from the value by whitespace, or it is part of it.
            return null;
        }
        return new int[]{valueEnd + hash + 1, lineEnd};
    }

    // ------------------------------------------------------------------ the splice

    private record Located(MappingNode parent, NodeTuple tuple) {
    }

    private @Nullable Located locate(List<String> path) {
        Node node = compose(text);
        for (int i = 0; i < path.size(); i++) {
            if (!(node instanceof MappingNode mapping)) {
                return null;
            }
            NodeTuple tuple = find(mapping, path.get(i));
            if (tuple == null) {
                return null;
            }
            if (i == path.size() - 1) {
                return new Located(mapping, tuple);
            }
            node = tuple.getValueNode();
        }
        return null;
    }

    private void apply(List<String> path, Object value) {
        String rendered = render(value);
        Node root = compose(text);
        if (root == null) {
            // Empty document, or comments only: everything is appended at the top level.
            append(nest(path, rendered, 0));
            return;
        }
        Node node = root;
        for (int i = 0; i < path.size(); i++) {
            String segment = path.get(i);
            String where = String.join(".", path.subList(0, i));
            if (!(node instanceof MappingNode mapping)) {
                throw new IllegalArgumentException(String.join(".", path) + ": '"
                        + (where.isEmpty() ? "<root>" : where) + "' is not a section");
            }
            NodeTuple tuple = find(mapping, segment);
            List<String> rest = path.subList(i, path.size());
            if (tuple == null) {
                insertInto(mapping, rest, rendered, String.join(".", path));
                return;
            }
            Node child = tuple.getValueNode();
            if (i == path.size() - 1) {
                replace(tuple, value, rendered);
                return;
            }
            if (isNull(child)) {
                // "key:" with nothing under it — a section that has not been started yet.
                List<String> below = path.subList(i + 1, path.size());
                int column = tuple.getKeyNode().getStartMark().getColumn() + 2;
                int at = lineEndExclusive(index(tuple.getKeyNode().getStartMark()));
                text = text.substring(0, at) + newline + nest(below, rendered, column)
                        + text.substring(at);
                return;
            }
            node = child;
        }
    }

    /** Replaces the value of an existing key. */
    private void replace(NodeTuple tuple, Object value, String rendered) {
        Node old = tuple.getValueNode();
        if (value instanceof String string && old instanceof ScalarNode scalar) {
            // A value the operator quoted stays quoted, and in their quote style.
            if (scalar.getScalarStyle() == DumperOptions.ScalarStyle.SINGLE_QUOTED
                    && string.indexOf('\n') < 0) {
                rendered = "'" + string.replace("'", "''") + "'";
            } else if (scalar.getScalarStyle() == DumperOptions.ScalarStyle.DOUBLE_QUOTED) {
                rendered = doubleQuoted(string);
            }
        }
        int afterColon = text.indexOf(':', index(tuple.getKeyNode().getEndMark())) + 1;
        if (isNull(old)) {
            // "key:" with nothing after it.
            text = text.substring(0, afterColon) + " " + rendered + text.substring(afterColon);
            return;
        }
        int start;
        String prefix = "";
        boolean blockBelow = (old instanceof CollectionNode<?> collection
                && collection.getFlowStyle() == DumperOptions.FlowStyle.BLOCK)
                || old.getStartMark().getLine() > tuple.getKeyNode().getEndMark().getLine();
        if (blockBelow) {
            // A block list (or a scalar pushed onto the next line) becomes an inline value right
            // after the colon. Anything between the colon and the old value goes with it.
            start = afterColon;
            prefix = " ";
        } else {
            start = index(old.getStartMark());
        }
        int stop = end(old);
        text = text.substring(0, start) + prefix + rendered + text.substring(stop);
    }

    /** Adds the missing tail of a path at the end of a block mapping. */
    private void insertInto(MappingNode mapping, List<String> rest, String rendered, String full) {
        if (mapping.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            throw new UnsupportedOperationException(full
                    + ": adding a key to a flow mapping ({ ... }) is not supported");
        }
        List<NodeTuple> tuples = mapping.getValue();
        if (tuples.isEmpty()) {
            append(nest(rest, rendered, 0));
            return;
        }
        int column = tuples.get(0).getKeyNode().getStartMark().getColumn();
        int at = lineEndExclusive(end(tuples.get(tuples.size() - 1).getValueNode()));
        text = text.substring(0, at) + newline + nest(rest, rendered, column) + text.substring(at);
    }

    private void append(String block) {
        if (!text.isEmpty() && !text.endsWith("\n")) {
            text += newline;
        }
        text += block + newline;
    }

    /** {@code a: / b: / c: value}, one level deeper per segment, no trailing newline. */
    private String nest(List<String> path, String rendered, int column) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) {
                out.append(newline);
            }
            out.append(" ".repeat(column + 2 * i)).append(key(path.get(i))).append(':');
            if (i == path.size() - 1) {
                out.append(' ').append(rendered);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ rendering

    /** How a value is spelled in the file. */
    public static String render(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("null is not written - use remove()");
        }
        if (value instanceof Boolean bool) {
            return bool.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("not a finite number: " + number);
            }
            // Six places is far below anything this plugin reads (speeds are hundredths), and it
            // keeps 0.1 + 0.2 from reaching the file as 0.30000000000000004.
            BigDecimal decimal = new BigDecimal(number).setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros();
            String plain = decimal.toPlainString();
            return plain.contains(".") ? plain : plain + ".0";
        }
        if (value instanceof Number number) {
            return String.valueOf(number.longValue());
        }
        if (value instanceof String string) {
            return plainSafe(string) ? string : doubleQuoted(string);
        }
        if (value instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(render(item));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        throw new IllegalArgumentException("cannot write a " + value.getClass().getSimpleName()
                + " - only booleans, numbers, strings and lists of those");
    }

    private static final Resolver RESOLVER = new Resolver();

    /** Whether a string can go in unquoted and still be read back as the same string. */
    public static boolean plainSafe(String value) {
        if (value.isEmpty() || !value.equals(value.strip())) {
            return false;
        }
        if ("-?:,[]{}#&*!|>'\"%@`".indexOf(value.charAt(0)) >= 0) {
            return false;
        }
        if (value.contains(": ") || value.contains(" #") || value.endsWith(":")) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ',' || c == '[' || c == ']' || c == '{' || c == '}') {
                // The brackets and comma are harmless in a block value but break the same string
                // the moment it lands inside a flow list.
                return false;
            }
        }
        // "true", "no", "1.5", "null", "~" — anything the loader would turn into a non-string.
        return RESOLVER.resolve(NodeId.scalar, value, true).equals(Tag.STR);
    }

    static String doubleQuoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String key(String key) {
        return plainSafe(key) ? key : doubleQuoted(key);
    }

    // ------------------------------------------------------------------ positions

    private static Node compose(String text) {
        try {
            return new Yaml(new LoaderOptions()).compose(new StringReader(text));
        } catch (YAMLException error) {
            throw new IllegalArgumentException("not valid YAML: " + error.getMessage(), error);
        }
    }

    private static @Nullable NodeTuple find(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) {
                return tuple;
            }
        }
        return null;
    }

    private static boolean isNull(Node node) {
        return node instanceof ScalarNode scalar && scalar.getTag().equals(Tag.NULL)
                && scalar.getValue().isEmpty();
    }

    /**
     * Where a node's text really ends. A block collection's own end mark sits at the start of
     * whatever follows it (the next key, a comment, a blank line), so it is taken from its last
     * child instead; and trailing whitespace is never part of a value.
     */
    private int end(Node node) {
        int end;
        if (node instanceof SequenceNode sequence
                && sequence.getFlowStyle() == DumperOptions.FlowStyle.BLOCK
                && !sequence.getValue().isEmpty()) {
            end = end(sequence.getValue().get(sequence.getValue().size() - 1));
        } else if (node instanceof MappingNode mapping
                && mapping.getFlowStyle() == DumperOptions.FlowStyle.BLOCK
                && !mapping.getValue().isEmpty()) {
            end = end(mapping.getValue().get(mapping.getValue().size() - 1).getValueNode());
        } else {
            end = index(node.getEndMark());
        }
        int start = index(node.getStartMark());
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /** SnakeYAML counts code points; Java strings count UTF-16 units. */
    private int index(Mark mark) {
        return text.offsetByCodePoints(0, Math.min(mark.getIndex(),
                text.codePointCount(0, text.length())));
    }

    private int lineStart(int index) {
        int newlineAt = text.lastIndexOf('\n', index - 1);
        return newlineAt < 0 ? 0 : newlineAt + 1;
    }

    /** Index of the line break ending the line that holds {@code index} (before a {@code \r}). */
    private int lineEndExclusive(int index) {
        int newlineAt = text.indexOf('\n', index);
        if (newlineAt < 0) {
            return text.length();
        }
        return newlineAt > 0 && text.charAt(newlineAt - 1) == '\r' ? newlineAt - 1 : newlineAt;
    }

    /** Just past the line break ending the line that holds {@code index}. */
    private int lineEndInclusive(int index) {
        int newlineAt = text.indexOf('\n', index);
        return newlineAt < 0 ? text.length() : newlineAt + 1;
    }
}
