package modsettings.model;

/** The value types a sidecar can declare. */
public enum ValueType {
    BOOL("bool"), INT("int"), FLOAT("float"), ENUM("enum"), STRING("string");

    public final String id;

    ValueType(String id) {
        this.id = id;
    }

    /** What a value of this type is, for "the file holds X, not ...". */
    public String described() {
        return switch (this) {
            case BOOL -> "true or false";
            case INT -> "a whole number";
            case FLOAT -> "a number";
            case ENUM, STRING -> "text";
        };
    }

    public static ValueType of(String id) {
        for (ValueType t : values()) if (t.id.equals(id)) return t;
        return null;
    }
}
