package modsettings;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.time.Clock;
import java.util.Map;
import modsettings.adapter.HytalePlatform;
import modsettings.adapter.SettingsCommand;
import modsettings.app.OwnConfig;
import modsettings.app.SettingsController;
import modsettings.app.TextCommands;
import modsettings.model.SidecarParser;

/**
 * Mod Settings Manager: an in-game settings window for every plugin's {@code config.json}. Wires the
 * Hytale adapter to the controller; at boot it finds every plugin's config and sidecar and checks
 * whether a mod put back a value written before the restart.
 */
public final class ModSettingsPlugin extends JavaPlugin {
    private static volatile ModSettingsPlugin instance;

    private SettingsController controller;
    private OwnConfig own;

    public ModSettingsPlugin(JavaPluginInit init) {
        super(init);
    }

    /** The running instance (for the test fixtures' scenarios). */
    public static ModSettingsPlugin get() {
        return instance;
    }

    public SettingsController controller() {
        return controller;
    }

    @Override
    protected void setup() {
        instance = this;
        own = new OwnConfig(getDataDirectory().resolve("config.json"));
        String problem = own.load();
        if (problem != null) getLogger().atWarning().log("[ModSettingsManager] %s", problem);

        HytalePlatform platform = new HytalePlatform(getLogger());
        controller = new SettingsController(platform, getDataDirectory(), SidecarParser.bundled(), Clock.systemUTC());
        controller.setAnnounce(own.announce());
        TextCommands text = new TextCommands(controller, () -> controller.discover(own.hiddenPlugins()));

        PermissionsModule.registerPermission(SettingsController.PERMISSION);
        getCommandRegistry().registerCommand(new SettingsCommand(controller, text, getLogger()));
        getEventRegistry().register(BootEvent.class, e -> boot());
    }

    private void boot() {
        // Check for put-back values first, so the window never shows a mod without its warning.
        controller.checkAfterRestart();
        controller.discover(own.hiddenPlugins());
    }

    /** The live-apply hook from the sidecar contract, for Mod Settings Manager's own settings. */
    public boolean onSettingsChanged(Map<String, Object> changed) {
        String problem = own.load();
        if (problem != null) {
            getLogger().atWarning().log("[ModSettingsManager] %s", problem);
            return false;
        }
        controller.setAnnounce(own.announce());
        if (changed.keySet().stream().anyMatch(k -> k.startsWith(OwnConfig.HIDDEN_PLUGINS))) controller.discover(own.hiddenPlugins());
        return true;
    }
}
