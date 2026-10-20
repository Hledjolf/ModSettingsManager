package modsettings.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import modsettings.model.Sidecar;

/**
 * A plugin with a config file the window can show.
 *
 * @param sidecar        the sidecar in use ({@link Sidecar#none} when there is none or it was rejected)
 * @param sidecarSource  where the sidecar came from, or null
 * @param sidecarErrors  why a sidecar was rejected (empty when it was accepted or absent)
 * @param searchText     lower-case text the search box matches: names, keys and labels
 */
public record ModEntry(String id, String name, String group, String version, String description, Path dataDir, Path configFile,
                       Sidecar sidecar, String sidecarSource, List<String> sidecarErrors, List<String> sidecarWarnings,
                       boolean hasHook, String searchText) {
    public boolean hasSidecar() {
        return sidecarSource != null && sidecarErrors.isEmpty();
    }

    public boolean sidecarRejected() {
        return !sidecarErrors.isEmpty();
    }

    /** Case-insensitive match on the name, group, id, and the keys and labels of its settings. */
    public boolean matches(String query) {
        if (query == null || query.isBlank()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (String part : q.split("\\s+")) if (!searchText.contains(part)) return false;
        return true;
    }
}
