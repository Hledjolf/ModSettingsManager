package modsettings.model;

/** The value types a sidecar can declare. */
public enum ValueType {
    BOOL("bool"), INT("int"), FLOAT("float"), ENUM("enum"), STRING("string");

    public final String id;

    ValueType(String id) {
        this.id = id;
    }

    public static ValueType of(String id) {
        for (ValueType t : values()) if (t.id.equals(id)) return t;
        return null;
    }
}
