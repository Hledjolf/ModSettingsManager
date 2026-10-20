package modsettings.model;

import java.util.Objects;
import modsettings.json.Json;
import modsettings.model.Control.Kind;

/**
 * Turns what a control sends (or what a command types) into a config value, and checks it: type, range
 * and options. Values come back as the JSON types the config uses: {@code Boolean}, {@code Long},
 * {@code Double} or {@code String}.
 */
public final class Values {
    private Values() {}

    /** Either a value or an error message for the player. */
    public record Parsed(Object value, String error) {
        public boolean ok() {
            return error == null;
        }

        static Parsed of(Object v) {
            return new Parsed(v, null);
        }

        static Parsed bad(String msg) {
            return new Parsed(null, msg);
        }
    }

    /** Longest string a text setting accepts. */
    public static final int MAX_STRING = 4000;

    public static Parsed parse(Control c, Object raw) {
        if (!c.editable()) return Parsed.bad(c.label() + " is read-only here; edit the file");
        if (raw == null) return Parsed.bad(c.label() + ": no value");
        return switch (c.kind()) {
            case BOOL -> {
                if (raw instanceof Boolean b) yield Parsed.of(b);
                String s = raw.toString().trim().toLowerCase();
                if (s.equals("true") || s.equals("on") || s.equals("yes")) yield Parsed.of(Boolean.TRUE);
                if (s.equals("false") || s.equals("off") || s.equals("no")) yield Parsed.of(Boolean.FALSE);
                yield Parsed.bad(c.label() + ": expected true or false, got \"" + raw + "\"");
            }
            case INT -> {
                Double d = number(raw);
                if (d == null) yield Parsed.bad(c.label() + ": \"" + raw + "\" is not a number");
                if (d != Math.rint(d)) yield Parsed.bad(c.label() + ": must be a whole number, got " + Json.formatDouble(d));
                if (Math.abs(d) > 9.0e15) yield Parsed.bad(c.label() + ": number too large");
                String range = range(c, d);
                if (range != null) yield Parsed.bad(range);
                // Keep the file's own number style: a file holding 1200.0 keeps a fraction.
                yield Parsed.of(c.value() instanceof Double ? (Object) d : (Object) (long) d.doubleValue());
            }
            case FLOAT -> {
                Double d = number(raw);
                if (d == null) yield Parsed.bad(c.label() + ": \"" + raw + "\" is not a number");
                if (d.isNaN() || d.isInfinite()) yield Parsed.bad(c.label() + ": must be a finite number");
                String range = range(c, d);
                if (range != null) yield Parsed.bad(range);
                // A float setting whose file value is a whole number written without a fraction keeps that style.
                if (c.value() instanceof Long && d == Math.rint(d) && Math.abs(d) < 9.0e15) yield Parsed.of((long) d.doubleValue());
                yield Parsed.of(d);
            }
            case ENUM -> {
                String s = raw instanceof String str ? str : raw.toString();
                if (c.options() == null || !c.options().contains(s)) yield Parsed.bad(c.label() + ": \"" + s + "\" is not one of " + c.options());
                yield Parsed.of(s);
            }
            case STRING -> {
                String s = raw instanceof String str ? str : raw instanceof Double d && d == Math.rint(d) ? Long.toString((long) d.doubleValue()) : raw.toString();
                if (s.length() > MAX_STRING) yield Parsed.bad(c.label() + ": longer than " + MAX_STRING + " characters");
                yield Parsed.of(s);
            }
            case READONLY -> Parsed.bad(c.label() + " is read-only");
        };
    }

    private static String range(Control c, double d) {
        if (c.min() != null && d < c.min()) return c.label() + ": " + Control.num(d) + " is below the minimum " + Control.num(c.min());
        if (c.max() != null && d > c.max()) return c.label() + ": " + Control.num(d) + " is above the maximum " + Control.num(c.max());
        return null;
    }

    private static Double number(Object raw) {
        if (raw instanceof Number n && !(raw instanceof Boolean)) return n.doubleValue();
        if (raw instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return null;
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * True when two values of a control mean the same setting. Numbers compare by value; floats allow for
     * the number field's display rounding (six decimals), so an untouched field never counts as a change.
     */
    public static boolean same(Control c, Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y && !(a instanceof Boolean) && !(b instanceof Boolean)) {
            double p = x.doubleValue(), q = y.doubleValue();
            if (p == q) return true;
            if (c != null && c.kind() == Kind.FLOAT) return Math.abs(p - q) <= 5e-7 * Math.max(1.0, Math.max(Math.abs(p), Math.abs(q)));
            return false;
        }
        return Objects.equals(a, b);
    }

    /** A value for display in a text field or log line. */
    public static String display(Object v) {
        if (v instanceof String s) return s;
        return Json.write(v);
    }
}
