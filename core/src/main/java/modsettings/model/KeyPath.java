package modsettings.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dotted key paths into a decoded config: {@code "Seeder.CellSize"} addresses {@code CellSize} inside the
 * object {@code Seeder}. A key that literally contains a dot (one object key {@code "a.b"}) is found too:
 * at each level the longest literal key that matches the rest of the path wins.
 */
public final class KeyPath {
    private KeyPath() {}

    /** The value at {@code path}, or empty if any part is missing. A present JSON null is {@code Optional.empty()} too; see {@link #has}. */
    public static Optional<Object> get(Map<String, Object> root, String path) {
        Object v = find(root, path);
        return v == MISSING ? Optional.empty() : Optional.ofNullable(v);
    }

    public static boolean has(Map<String, Object> root, String path) {
        return find(root, path) != MISSING;
    }

    private static final Object MISSING = new Object();

    @SuppressWarnings("unchecked")
    private static Object find(Map<String, Object> obj, String path) {
        if (obj.containsKey(path)) return obj.get(path);
        // Try splits from the longest head that is a key, so "a.b.c" with key "a.b" resolves to obj["a.b"]["c"].
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String head = path.substring(0, dot);
            if (obj.containsKey(head) && obj.get(head) instanceof Map<?, ?> child) {
                Object v = find((Map<String, Object>) child, path.substring(dot + 1));
                if (v != MISSING) return v;
            }
            dot = path.lastIndexOf('.', dot - 1);
        }
        return MISSING;
    }

    /**
     * Sets the value at {@code path}, creating intermediate objects as needed. An existing key keeps its
     * position; a new key is appended at the end of its object.
     */
    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> root, String path, Object value) {
        if (root.containsKey(path) || path.indexOf('.') < 0) {
            root.put(path, value);
            return;
        }
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String head = path.substring(0, dot);
            if (root.get(head) instanceof Map<?, ?> child) {
                set((Map<String, Object>) child, path.substring(dot + 1), value);
                return;
            }
            dot = path.lastIndexOf('.', dot - 1);
        }
        // Nothing on the way exists: create objects for every part but the last.
        String first = path.substring(0, path.indexOf('.'));
        Object existing = root.get(first);
        if (existing != null && !(existing instanceof Map)) {
            throw new IllegalArgumentException("cannot set " + path + ": " + first + " is not an object");
        }
        Map<String, Object> child = (Map<String, Object>) existing;
        if (child == null) {
            child = new LinkedHashMap<>();
            root.put(first, child);
        }
        set(child, path.substring(first.length() + 1), value);
    }

    /**
     * Every scalar leaf under {@code obj} as dotted paths (prefixed), in file order. Lists and empty
     * objects are leaves too, since they are shown as single read-only values.
     */
    @SuppressWarnings("unchecked")
    public static List<String> leaves(Map<String, Object> obj, String prefix) {
        List<String> out = new ArrayList<>();
        for (var e : obj.entrySet()) {
            String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m && !m.isEmpty()) out.addAll(leaves((Map<String, Object>) m, p));
            else out.add(p);
        }
        return out;
    }

    /** The top-level part of a dotted path as it resolves in {@code root} (handles literal dotted keys). */
    public static String topKey(Map<String, Object> root, String path) {
        if (root.containsKey(path)) return path;
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            String head = path.substring(0, dot);
            if (root.containsKey(head)) return head;
            dot = path.lastIndexOf('.', dot - 1);
        }
        int first = path.indexOf('.');
        return first < 0 ? path : path.substring(0, first);
    }
}
