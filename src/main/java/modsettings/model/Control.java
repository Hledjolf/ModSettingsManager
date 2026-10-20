package modsettings.model;

import java.util.List;

/**
 * One row of the settings window: a config key with the control chosen for it.
 *
 * @param key         dotted key path into the config
 * @param label       what the row shows (sidecar label, else the key made readable)
 * @param description tooltip text, may be null
 * @param sectionId   the sidecar section, or null for the unsectioned group
 * @param kind        the control
 * @param options     dropdown entries for {@link Kind#ENUM}
 * @param defaultValue the sidecar default, or null
 * @param restart     the sidecar's {@code restart} flag, null when not given
 * @param value       the current value in the file (the default if {@code inFile} is false)
 * @param inFile      false when the key is only in the sidecar
 * @param note        a short explanation shown on the row (for example why it is read-only), or null
 */
public record Control(String key, String label, String description, String sectionId, Kind kind, Double min, Double max, Double step,
                      List<String> options, Object defaultValue, Boolean restart, Object value, boolean inFile, String note) {
    public enum Kind { BOOL, INT, FLOAT, ENUM, STRING, READONLY }

    public boolean editable() {
        return kind != Kind.READONLY;
    }

    public boolean hasDefault() {
        return defaultValue != null && editable();
    }

    /** The hint shown after the control: range, step, default, restart. */
    public String hint() {
        StringBuilder b = new StringBuilder();
        if (min != null && max != null) b.append(num(min)).append(" to ").append(num(max));
        else if (min != null) b.append("at least ").append(num(min));
        else if (max != null) b.append("at most ").append(num(max));
        if (step != null && kind != Kind.BOOL) append(b, "step " + num(step));
        if (Boolean.TRUE.equals(restart)) append(b, "restart");
        if (note != null) append(b, note);
        return b.toString();
    }

    private static void append(StringBuilder b, String s) {
        if (!b.isEmpty()) b.append(", ");
        b.append(s);
    }

    static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
    }
}
