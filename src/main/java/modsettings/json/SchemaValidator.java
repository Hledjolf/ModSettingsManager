package modsettings.json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validates decoded JSON against the JSON Schema (draft 2020-12) subset the sidecar schema uses:
 * {@code type, properties, required, additionalProperties, propertyNames, enum, const, pattern,
 * minLength, maxLength, minimum, maximum, exclusiveMinimum, exclusiveMaximum, items, minItems, maxItems,
 * uniqueItems, $ref} (local {@code #/$defs/...}), {@code allOf, anyOf, oneOf}. Annotation keywords
 * ({@code title, description, default, $comment, $schema, $id}) are ignored.
 */
public final class SchemaValidator {
    private final Map<String, Object> root;

    public SchemaValidator(Map<String, Object> schemaRoot) {
        this.root = schemaRoot;
    }

    /** Problems found, each as {@code <json path>: <message>}; empty when the document is valid. */
    public List<String> validate(Object doc) {
        List<String> errors = new ArrayList<>();
        check(doc, root, "$", errors);
        return errors;
    }

    @SuppressWarnings("unchecked")
    private void check(Object v, Map<String, Object> schema, String path, List<String> errors) {
        if (schema.get("$ref") instanceof String ref) {
            Map<String, Object> target = resolve(ref);
            if (target == null) {
                errors.add(path + ": schema error, unresolved $ref " + ref);
                return;
            }
            check(v, target, path, errors);
        }
        if (schema.containsKey("type") && !typeMatches(v, schema.get("type"))) {
            errors.add(path + ": expected " + describeType(schema.get("type")) + ", got " + Json.typeName(v));
            return;
        }
        if (schema.containsKey("const") && !jsonEquals(schema.get("const"), v)) {
            errors.add(path + ": must be " + Json.write(schema.get("const")));
        }
        if (schema.get("enum") instanceof List<?> options && options.stream().noneMatch(o -> jsonEquals(o, v))) {
            errors.add(path + ": " + Json.write(v) + " is not one of " + Json.write(options));
        }
        if (v instanceof String str) {
            if (schema.get("pattern") instanceof String p && !Pattern.compile(p).matcher(str).find()) {
                errors.add(path + ": \"" + str + "\" does not match " + p);
            }
            if (schema.containsKey("minLength") && str.length() < num(schema, "minLength")) {
                errors.add(path + ": shorter than " + (long) num(schema, "minLength") + " characters");
            }
            if (schema.containsKey("maxLength") && str.length() > num(schema, "maxLength")) {
                errors.add(path + ": longer than " + (long) num(schema, "maxLength") + " characters");
            }
        }
        if (v instanceof Number n && !(v instanceof Boolean)) {
            double d = n.doubleValue();
            if (schema.containsKey("minimum") && d < num(schema, "minimum")) errors.add(path + ": " + Json.write(v) + " is below the minimum " + fmt(num(schema, "minimum")));
            if (schema.containsKey("maximum") && d > num(schema, "maximum")) errors.add(path + ": " + Json.write(v) + " is above the maximum " + fmt(num(schema, "maximum")));
            if (schema.containsKey("exclusiveMinimum") && d <= num(schema, "exclusiveMinimum")) errors.add(path + ": " + Json.write(v) + " must be above " + fmt(num(schema, "exclusiveMinimum")));
            if (schema.containsKey("exclusiveMaximum") && d >= num(schema, "exclusiveMaximum")) errors.add(path + ": " + Json.write(v) + " must be below " + fmt(num(schema, "exclusiveMaximum")));
        }
        if (v instanceof List<?> list) {
            if (schema.containsKey("minItems") && list.size() < num(schema, "minItems")) errors.add(path + ": needs at least " + (long) num(schema, "minItems") + " items");
            if (schema.containsKey("maxItems") && list.size() > num(schema, "maxItems")) errors.add(path + ": allows at most " + (long) num(schema, "maxItems") + " items");
            if (Boolean.TRUE.equals(schema.get("uniqueItems")) && new HashSet<>(list).size() != list.size()) errors.add(path + ": items must be unique");
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int k = 0; k < list.size(); k++) check(list.get(k), (Map<String, Object>) items, path + "[" + k + "]", errors);
            }
        }
        if (v instanceof Map<?, ?> raw) {
            Map<String, Object> obj = (Map<String, Object>) raw;
            if (schema.get("required") instanceof List<?> req) {
                for (Object r : req) if (!obj.containsKey((String) r)) errors.add(path + ": missing required \"" + r + "\"");
            }
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            Object additional = schema.get("additionalProperties");
            for (var e : obj.entrySet()) {
                String child = path + "." + e.getKey();
                if (schema.get("propertyNames") instanceof Map<?, ?> names) {
                    List<String> nameErrors = new ArrayList<>();
                    check(e.getKey(), (Map<String, Object>) names, child, nameErrors);
                    for (String ne : nameErrors) errors.add(path + ": bad key name \"" + e.getKey() + "\" (" + ne.substring(ne.indexOf(": ") + 2) + ")");
                }
                if (props.containsKey(e.getKey())) {
                    check(e.getValue(), (Map<String, Object>) props.get(e.getKey()), child, errors);
                } else if (Boolean.FALSE.equals(additional)) {
                    errors.add(path + ": unknown property \"" + e.getKey() + "\"");
                } else if (additional instanceof Map<?, ?> addSchema) {
                    check(e.getValue(), (Map<String, Object>) addSchema, child, errors);
                }
            }
        }
        if (schema.get("allOf") instanceof List<?> all) {
            for (Object s : all) check(v, (Map<String, Object>) s, path, errors);
        }
        if (schema.get("anyOf") instanceof List<?> any) {
            if (any.stream().noneMatch(s -> passes(v, (Map<String, Object>) s, path))) errors.add(path + ": matches none of the allowed forms");
        }
        if (schema.get("oneOf") instanceof List<?> one) {
            long n = one.stream().filter(s -> passes(v, (Map<String, Object>) s, path)).count();
            if (n != 1) errors.add(path + ": must match exactly one allowed form (matched " + n + ")");
        }
    }

    private boolean passes(Object v, Map<String, Object> schema, String path) {
        List<String> scratch = new ArrayList<>();
        check(v, schema, path, scratch);
        return scratch.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolve(String ref) {
        if (ref.equals("#")) return root;
        if (!ref.startsWith("#/")) return null;
        Object cur = root;
        for (String part : ref.substring(2).split("/")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = m.get(part.replace("~1", "/").replace("~0", "~"));
        }
        return cur instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static boolean typeMatches(Object v, Object type) {
        if (type instanceof List<?> types) return types.stream().anyMatch(t -> typeMatches(v, t));
        return switch ((String) type) {
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "null" -> v == null;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Long || v instanceof Integer || (v instanceof Double d && d == Math.rint(d) && !d.isInfinite());
            default -> false;
        };
    }

    private static String describeType(Object type) {
        return type instanceof List<?> l ? String.join(" or ", l.stream().map(String::valueOf).toList()) : String.valueOf(type);
    }

    /** JSON equality: numbers compare by value, so 1 equals 1.0. */
    static boolean jsonEquals(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y && !(a instanceof Boolean) && !(b instanceof Boolean)) {
            return x.doubleValue() == y.doubleValue();
        }
        return Objects.equals(a, b);
    }

    private static double num(Map<String, Object> schema, String key) {
        return ((Number) schema.get(key)).doubleValue();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
