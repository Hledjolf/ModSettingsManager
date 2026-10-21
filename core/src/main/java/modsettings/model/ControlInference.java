package modsettings.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import modsettings.json.Json;
import modsettings.model.Control.Kind;

/**
 * Chooses a control for every key of a config, using the sidecar where it has an entry:
 *
 * <table>
 *   <tr><th>Value</th><th>Control</th></tr>
 *   <tr><td>true / false</td><td>checkbox</td></tr>
 *   <tr><td>whole number</td><td>integer field</td></tr>
 *   <tr><td>number with a fraction</td><td>number field</td></tr>
 *   <tr><td>string</td><td>text field (a dropdown when the sidecar gives options)</td></tr>
 *   <tr><td>list, object, null</td><td>read-only</td></tr>
 * </table>
 *
 * A nested object is one read-only row unless the sidecar names a dotted key inside it; then each of its
 * values gets its own row. Hidden keys (and everything under a hidden object) are left out. Keys only in
 * the sidecar are shown when they have a default.
 */
public final class ControlInference {
    private ControlInference() {}

    /** A titled group of rows. {@code id} is null for the unsectioned group; {@code title} is null when it has no header. */
    public record Group(String id, String title, String description, List<Control> controls) {}

    public static final String OTHER_TITLE = "Other";

    public static List<Group> layout(Map<String, Object> config, Sidecar sidecar) {
        List<Control> controls = controls(config, sidecar);
        List<Group> groups = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        for (Sidecar.Section s : sidecar.sections()) {
            known.add(s.id());
            List<Control> in = controls.stream().filter(c -> s.id().equals(c.sectionId())).toList();
            if (!in.isEmpty()) groups.add(new Group(s.id(), s.displayLabel(), s.description(), in));
        }
        List<Control> rest = controls.stream().filter(c -> c.sectionId() == null || !known.contains(c.sectionId())).toList();
        if (!rest.isEmpty()) groups.add(new Group(null, groups.isEmpty() ? null : OTHER_TITLE, null, rest));
        return groups;
    }

    /** All visible controls: sidecar-declared keys in sidecar order, then the rest in file order. */
    public static List<Control> controls(Map<String, Object> config, Sidecar sidecar) {
        Map<String, Boolean> candidates = new LinkedHashMap<>(); // key -> in file
        for (var e : config.entrySet()) {
            String top = e.getKey();
            if (e.getValue() instanceof Map<?, ?> m && !m.isEmpty() && sidecarReachesInto(config, sidecar, top)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                for (String leaf : KeyPath.leaves(child, top)) candidates.put(leaf, true);
            } else {
                candidates.put(top, true);
            }
        }
        for (var e : sidecar.keys().entrySet()) {
            if (!KeyPath.has(config, e.getKey()) && e.getValue().defaultValue() != null) candidates.putIfAbsent(e.getKey(), false);
        }

        List<Control> declared = new ArrayList<>();
        for (String key : sidecar.keys().keySet()) {
            if (candidates.containsKey(key) && !hidden(sidecar, key)) declared.add(control(config, sidecar, key, candidates.get(key)));
        }
        List<Control> out = new ArrayList<>(declared);
        for (var c : candidates.entrySet()) {
            if (sidecar.keys().containsKey(c.getKey()) || hidden(sidecar, c.getKey())) continue;
            out.add(control(config, sidecar, c.getKey(), c.getValue()));
        }
        return out;
    }

    private static boolean sidecarReachesInto(Map<String, Object> config, Sidecar sidecar, String top) {
        for (String k : sidecar.keys().keySet()) {
            if (k.startsWith(top + ".") && KeyPath.topKey(config, k).equals(top)) return true;
        }
        return false;
    }

    /** Hidden by its own entry or by a hidden parent object. */
    static boolean hidden(Sidecar sidecar, String key) {
        if (sidecar.key(key).hidden()) return true;
        int dot = key.lastIndexOf('.');
        while (dot > 0) {
            if (sidecar.key(key.substring(0, dot)).hidden()) return true;
            dot = key.lastIndexOf('.', dot - 1);
        }
        return false;
    }

    public static Control control(Map<String, Object> config, Sidecar sidecar, String key, boolean inFile) {
        Sidecar.KeySpec spec = sidecar.key(key);
        Optional<Object> found = KeyPath.get(config, key);
        Object value = inFile ? found.orElse(null) : spec.defaultValue();
        String label = spec.label() != null && !spec.label().isBlank() ? spec.label() : humanize(key);
        String note = inFile ? null : "not in the file yet";
        Kind kind;
        List<String> options = null;
        ValueType type = spec.type();
        if (type == null) {
            kind = inferred(value);
        } else {
            kind = switch (type) {
                case BOOL -> Kind.BOOL;
                case INT -> Kind.INT;
                case FLOAT -> Kind.FLOAT;
                case ENUM -> Kind.ENUM;
                case STRING -> Kind.STRING;
            };
            // A value of the wrong type still gets the declared control, so it can be fixed.
            boolean fits = switch (type) {
                case BOOL -> value instanceof Boolean;
                case INT -> value instanceof Long || value instanceof Integer || value instanceof Double d && d == Math.rint(d) && !d.isInfinite();
                case FLOAT -> value instanceof Number;
                case ENUM, STRING -> value instanceof String;
            };
            if (!fits) note = "the file holds " + Json.write(value) + ", not " + type.described() + "; fix this value";
        }
        if (kind == Kind.ENUM) {
            options = new ArrayList<>(spec.options() == null ? List.of() : spec.options());
            if (value instanceof String s && !options.contains(s)) {
                options.add(0, s);
                note = "current value is not one of the options";
            }
        }
        if (kind == Kind.READONLY && note == null) note = value instanceof List ? "list, edit the file" : value instanceof Map ? "object, edit the file" : null;
        boolean numeric = kind == Kind.INT || kind == Kind.FLOAT;
        return new Control(key, label, spec.description(), spec.section(), kind,
            numeric ? spec.min() : null, numeric ? spec.max() : null, numeric ? spec.step() : null,
            options == null ? null : List.copyOf(options), kind == Kind.READONLY ? null : spec.defaultValue(), spec.restart(), value, inFile, note);
    }

    static Kind inferred(Object value) {
        if (value instanceof Boolean) return Kind.BOOL;
        if (value instanceof Long || value instanceof Integer) return Kind.INT;
        if (value instanceof Number) return Kind.FLOAT;
        if (value instanceof String) return Kind.STRING;
        return Kind.READONLY;
    }

    /** {@code "DaySeconds"} becomes "Day Seconds", {@code "Seeder.cell_size"} becomes "Seeder / Cell Size". */
    public static String humanize(String key) {
        List<String> parts = new ArrayList<>();
        for (String seg : key.split("\\.")) parts.add(words(seg));
        return String.join(" / ", parts);
    }

    private static String words(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '-') {
                if (!b.isEmpty() && b.charAt(b.length() - 1) != ' ') b.append(' ');
                continue;
            }
            boolean boundary = i > 0 && Character.isUpperCase(c)
                && (Character.isLowerCase(s.charAt(i - 1)) || Character.isDigit(s.charAt(i - 1))
                    || i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1)) && Character.isUpperCase(s.charAt(i - 1)));
            if (boundary && !b.isEmpty() && b.charAt(b.length() - 1) != ' ') b.append(' ');
            boolean wordStart = b.isEmpty() || b.charAt(b.length() - 1) == ' ';
            b.append(wordStart ? Character.toUpperCase(c) : c);
        }
        return b.toString().trim();
    }
}
