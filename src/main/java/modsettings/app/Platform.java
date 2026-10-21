package modsettings.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import modsettings.model.Changes.HookOutcome;

/**
 * What the controller needs from the server. The Hytale implementation lives in {@code modsettings.adapter};
 * tests use a fake.
 */
public interface Platform {
    /** One loaded plugin. {@code bundledSidecar} is the text of {@code Settings/<Group>_<Name>.settings.json} in its jar, or null. */
    record PluginInfo(String group, String name, String version, String description, Path dataDir,
                      String bundledSidecar, String bundledSidecarSource) {
        public String id() {
            return group + ":" + name;
        }

        public String fileStem() {
            return group + "_" + name;
        }
    }

    List<PluginInfo> plugins();

    /** Operator status or the {@code modsettings.edit} permission. */
    boolean canEdit(UUID player);

    /** True if the plugin's main class has {@code public boolean onSettingsChanged(Map<String, Object>)}. */
    boolean hasHook(String pluginId);

    /**
     * Calls the plugin's hook on the server's default world thread. Completes with {@link HookOutcome#MISSING}
     * if there is no hook, never exceptionally.
     */
    CompletableFuture<HookOutcome> callHook(String pluginId, Map<String, Object> changed);

    /** Sends a chat line to every online player who may edit settings. */
    void tellEditors(String message);

    void info(String message);

    void warn(String message);
}
