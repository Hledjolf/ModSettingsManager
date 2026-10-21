package modsettings.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import modsettings.json.Json;
import modsettings.json.SchemaValidator;

/**
 * Reads a sidecar: JSON syntax, then the published schema ({@code settings-sidecar.schema.json}), then the
 * rules a schema can't express (the plugin id matches, min is not above max, defaults fit the type, range
 * and options, an enum has options). Errors reject the sidecar; warnings are logged and the sidecar is used.
 */
public final class SidecarParser {
    public record Result(Sidecar sidecar, List<String> errors, List<String> warnings) {
        public boolean ok() {
            return sidecar != null;
        }
    }

    private final SchemaValidator schema;

    public SidecarParser(Map<String, Object> schemaRoot) {
        this.schema = new SchemaValidator(schemaRoot);
    }

    /** The schema shipped in this jar ({@code modsettings/settings-sidecar.schema.json}). */
    public static SidecarParser bundled() {
        try (InputStream in = SidecarParser.class.getResourceAsStream("/modsettings/settings-sidecar.schema.json")) {
            if (in == null) throw new IllegalStateException("settings-sidecar.schema.json is missing from the jar");
            return new SidecarParser(Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the bundled sidecar schema", e);
        }
    }

    /**
     * @param expectedPlugin the plugin's {@code Group:Name}; the sidecar's {@code plugin} must match it
     *                       (case-insensitive). Null skips that check.
     */
    @SuppressWarnings("unchecked")
    public Result parse(String text, String expectedPlugin) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Object doc;
        try {
            doc = Json.parse(text);
        } catch (Json.ParseException e) {
            return new Result(null, List.of("not valid JSON: " + e.getMessage()), warnings);
        }
        if (doc instanceof Map<?, ?> m && m.get("format") instanceof Number f && f.doubleValue() > Sidecar.FORMAT) {
            return new Result(null, List.of("format " + Json.write(f) + " is newer than this Mod Settings Manager reads (format "
                + Sidecar.FORMAT + "); update Mod Settings Manager"), warnings);
        }
        errors.addAll(schema.validate(doc));
        if (!errors.isEmpty()) return new Result(null, errors, warnings);

        Map<String, Object> root = (Map<String, Object>) doc;
        String plugin = (String) root.get("plugin");
        if (expectedPlugin != null && !plugin.equalsIgnoreCase(expectedPlugin)) {
            errors.add("$.plugin: \"" + plugin + "\" does not match the plugin it was found for (" + expectedPlugin + ")");
        }
        String title = root.get("title") instanceof String t && !t.isBlank() ? t.trim() : null;
        String config = (String) root.getOrDefault("config", Sidecar.DEFAULT_CONFIG);

        List<Sidecar.Section> sections = new ArrayList<>();
        if (root.get("sections") instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> s = (Map<String, Object>) o;
                String id = (String) s.get("id");
                if (sections.stream().anyMatch(x -> x.id().equals(id))) errors.add("$.sections: section id \"" + id + "\" is used twice");
                sections.add(new Sidecar.Section(id, (String) s.get("label"), (String) s.get("description")));
            }
        }

        Map<String, Sidecar.KeySpec> keys = new LinkedHashMap<>();
        for (var e : ((Map<String, Object>) root.get("keys")).entrySet()) {
            String key = e.getKey();
            String at = "$.keys." + key;
            Map<String, Object> k = (Map<String, Object>) e.getValue();
            List<String> options = k.get("options") instanceof List<?> o ? (List<String>) o : null;
            ValueType type = k.containsKey("type") ? ValueType.of((String) k.get("type")) : null;
            if (type == null && options != null) type = ValueType.ENUM;
            Double min = num(k.get("min")), max = num(k.get("max")), step = num(k.get("step"));
            Object def = k.get("default");
            String section = (String) k.get("section");

            if (type == ValueType.ENUM && options == null) errors.add(at + ": type enum needs options");
            if (options != null && type != ValueType.ENUM) warnings.add(at + ": options are only used with type enum; ignored for type " + type.id);
            boolean numeric = type == null || type == ValueType.INT || type == ValueType.FLOAT;
            if (!numeric && (min != null || max != null || step != null)) warnings.add(at + ": min, max and step only apply to numbers; ignored");
            if (min != null && max != null && min > max) errors.add(at + ": min " + fmt(min) + " is above max " + fmt(max));
            if (type == ValueType.INT) {
                for (var b : new Object[][] {{"min", min}, {"max", max}, {"step", step}}) {
                    if (b[1] != null && (Double) b[1] != Math.rint((Double) b[1])) errors.add(at + ": " + b[0] + " must be a whole number for type int");
                }
            }
            if (section != null && sections.stream().noneMatch(s -> s.id().equals(section))) {
                warnings.add(at + ": section \"" + section + "\" is not in sections; the key is shown under Other");
            }
            if (def != null) {
                String bad = checkDefault(type, def, min, max, options);
                if (bad != null) errors.add(at + ".default: " + bad);
            }
            keys.put(key, new Sidecar.KeySpec((String) k.get("label"), (String) k.get("description"), section, type,
                min, max, step, options == null ? null : List.copyOf(options), def,
                (Boolean) k.get("restart"), Boolean.TRUE.equals(k.get("hidden"))));
        }
        if (!errors.isEmpty()) return new Result(null, errors, warnings);
        return new Result(new Sidecar(Sidecar.FORMAT, plugin, title, config, List.copyOf(sections), keys), errors, warnings);
    }

    private static String checkDefault(ValueType type, Object def, Double min, Double max, List<String> options) {
        if (type == null) {
            if (def instanceof Number n && (min != null && n.doubleValue() < min || max != null && n.doubleValue() > max)) return "outside min and max";
            return null;
        }
        switch (type) {
            case BOOL -> {
                if (!(def instanceof Boolean)) return "must be true or false for type bool";
            }
            case INT, FLOAT -> {
                if (!(def instanceof Number n)) return "must be a number for type " + type.id;
                if (type == ValueType.INT && n.doubleValue() != Math.rint(n.doubleValue())) return "must be a whole number for type int";
                if (min != null && n.doubleValue() < min || max != null && n.doubleValue() > max) return Json.write(def) + " is outside min and max";
            }
            case ENUM -> {
                if (!(def instanceof String s) || options == null || !options.contains(s)) return Json.write(def) + " is not one of the options";
            }
            case STRING -> {
                if (!(def instanceof String)) return "must be a string for type string";
            }
        }
        return null;
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
