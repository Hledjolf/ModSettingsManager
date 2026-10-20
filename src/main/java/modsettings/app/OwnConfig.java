package modsettings.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import modsettings.json.Json;
import modsettings.model.ConfigFile;

/**
 * Mod Settings Manager's own {@code config.json}. Missing keys are added with their defaults; unknown keys
 * are kept. It has a sidecar and a live-apply hook like any mod could.
 */
public final class OwnConfig {
    public static final String HIDDEN_PLUGINS = "HiddenPlugins";
    public static final String ANNOUNCE = "AnnounceChanges";

    private final Path file;
    private volatile Set<String> hiddenPlugins = Set.of();
    private volatile boolean announce;

    public OwnConfig(Path file) {
        this.file = file;
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(ANNOUNCE, Boolean.FALSE);
        d.put(HIDDEN_PLUGINS, new ArrayList<>());
        return d;
    }

    /** Loads the file (creating or completing it). Returns a problem to log, or null. */
    public String load() {
        Map<String, Object> cfg = defaults();
        String problem = null;
        try {
            if (Files.exists(file)) {
                ConfigFile.Read r = ConfigFile.read(file);
                boolean missing = false;
                for (var e : defaults().entrySet()) if (!r.config().containsKey(e.getKey())) missing = true;
                Map<String, Object> merged = new LinkedHashMap<>(r.config());
                for (var e : defaults().entrySet()) merged.putIfAbsent(e.getKey(), e.getValue());
                cfg = merged;
                if (missing) ConfigFile.write(file, merged, r.indent());
            } else {
                ConfigFile.write(file, cfg, "  ");
                Files.deleteIfExists(ConfigFile.backupOf(file));
            }
        } catch (IOException | Json.ParseException e) {
            problem = file + " is unreadable (" + e.getMessage() + "); using defaults and leaving the file alone";
        }
        apply(cfg);
        return problem;
    }

    private void apply(Map<String, Object> cfg) {
        Set<String> hidden = new LinkedHashSet<>();
        if (cfg.get(HIDDEN_PLUGINS) instanceof List<?> l) for (Object o : l) if (o instanceof String s) hidden.add(s);
        hiddenPlugins = Set.copyOf(hidden);
        announce = Boolean.TRUE.equals(cfg.get(ANNOUNCE));
    }

    /** Tell online editors in chat when someone changes a setting. */
    public boolean announce() {
        return announce;
    }

    public Set<String> hiddenPlugins() {
        return hiddenPlugins;
    }

    public Path file() {
        return file;
    }
}
