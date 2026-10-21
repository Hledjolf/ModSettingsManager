package modsettings.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed, validated sidecar ({@code Settings/<Group>_<Name>.settings.json}, format 1). See
 * {@code docs/SIDECAR.md} and {@code schema/settings-sidecar.schema.json}.
 */
public record Sidecar(int format, String plugin, String title, String config, List<Section> sections, Map<String, KeySpec> keys) {
    public static final int FORMAT = 1;
    public static final String DEFAULT_CONFIG = "config.json";

    public record Section(String id, String label, String description) {
        public String displayLabel() {
            return label == null || label.isBlank() ? id : label;
        }
    }

    /** One key's entry. Every field is optional; {@code null} means "not given". */
    public record KeySpec(String label, String description, String section, ValueType type, Double min, Double max, Double step,
                          List<String> options, Object defaultValue, Boolean restart, boolean hidden) {
        public static final KeySpec NONE = new KeySpec(null, null, null, null, null, null, null, null, null, null, false);
    }

    public KeySpec key(String key) {
        return keys.getOrDefault(key, KeySpec.NONE);
    }

    public Section section(String id) {
        for (Section s : sections) if (s.id().equals(id)) return s;
        return null;
    }

    /** An empty sidecar (no labels): what a mod without one gets. */
    public static Sidecar none(String plugin) {
        return new Sidecar(FORMAT, plugin, null, DEFAULT_CONFIG, List.of(), new LinkedHashMap<>());
    }
}
