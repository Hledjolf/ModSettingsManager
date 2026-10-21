package modsettings.adapter;

import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.Constants;
import com.hypixel.hytale.server.core.modules.singleplayer.SingleplayerModule;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import modsettings.app.Platform;
import modsettings.app.SettingsController;
import modsettings.model.Changes.HookOutcome;

/** {@link Platform} on the Hytale server: plugins from the PluginManager, permissions, the reflective hook. */
public final class HytalePlatform implements Platform {
    public static final String HOOK = "onSettingsChanged";
    /** A hook slower than this logs one warning: it stalls the world thread. */
    static final long SLOW_HOOK_MS = 1000;

    private final HytaleLogger logger;

    public HytalePlatform(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public List<PluginInfo> plugins() {
        List<PluginInfo> out = new ArrayList<>();
        for (PluginBase p : PluginManager.get().getPlugins()) {
            PluginManifest m = p.getManifest();
            if (m == null || p.getDataDirectory() == null) continue;
            String stem = m.getGroup() + "_" + m.getName() + ".settings.json";
            String bundled = null, source = null;
            if (p instanceof JavaPlugin jp && jp.getFile() != null) {
                Path file = jp.getFile();
                try {
                    bundled = readBundled(file, "Settings/" + stem);
                    if (bundled != null) source = file.getFileName() + "!/Settings/" + stem;
                } catch (IOException e) {
                    warn("[ModSettingsManager] cannot read " + file + ": " + e.getMessage());
                }
            }
            out.add(new PluginInfo(m.getGroup(), m.getName(), m.getVersion() == null ? null : m.getVersion().toString(),
                m.getDescription(), p.getDataDirectory(), bundled, source));
        }
        return out;
    }

    /** A text resource from a plugin jar (or an exploded plugin directory), or null. */
    static String readBundled(Path jarOrDir, String entry) throws IOException {
        if (Files.isDirectory(jarOrDir)) {
            Path f = jarOrDir.resolve(entry);
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        }
        if (!Files.isRegularFile(jarOrDir)) return null;
        try (ZipFile zf = new ZipFile(jarOrDir.toFile())) {
            ZipEntry e = zf.getEntry(entry);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Override
    public boolean canEdit(UUID player) {
        // In singleplayer the world's owner is not an operator, but vanilla's own admin commands let the owner
        // through; do the same, or the host could never apply a change in their own world.
        if (Constants.SINGLEPLAYER && SingleplayerModule.isOwner(player)) return true;
        PermissionsModule perms = PermissionsModule.get();
        return perms != null && perms.hasPermission(player, SettingsController.PERMISSION);
    }

    static PluginBase plugin(String pluginId) {
        for (PluginBase p : PluginManager.get().getPlugins()) {
            PluginManifest m = p.getManifest();
            if (m != null && (m.getGroup() + ":" + m.getName()).equalsIgnoreCase(pluginId)) return p;
        }
        return null;
    }

    /** The contract's hook method on the plugin's main class, or null. */
    static Method hookOf(Object plugin) {
        try {
            Method m = plugin.getClass().getMethod(HOOK, Map.class);
            if ((m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class) && !Modifier.isStatic(m.getModifiers())) return m;
        } catch (NoSuchMethodException e) {
            // no hook
        }
        return null;
    }

    @Override
    public boolean hasHook(String pluginId) {
        PluginBase p = plugin(pluginId);
        return p != null && hookOf(p) != null;
    }

    @Override
    public CompletableFuture<HookOutcome> callHook(String pluginId, Map<String, Object> changed) {
        PluginBase p = plugin(pluginId);
        Method m = p == null ? null : hookOf(p);
        if (m == null) return CompletableFuture.completedFuture(HookOutcome.MISSING);
        CompletableFuture<HookOutcome> f = new CompletableFuture<>();
        Runnable call = () -> {
            long t0 = System.nanoTime();
            try {
                Object r = m.invoke(p, changed);
                f.complete(Boolean.TRUE.equals(r) ? HookOutcome.LIVE : HookOutcome.RESTART);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logger.atWarning().withCause(cause).log("[ModSettingsManager] %s.%s threw: %s. The values are saved, but the mod could not apply them.", pluginId, HOOK, cause);
                f.complete(HookOutcome.FAILED);
            } catch (Throwable t) {
                logger.atWarning().withCause(t).log("[ModSettingsManager] could not call %s.%s: %s", pluginId, HOOK, t);
                f.complete(HookOutcome.FAILED);
            } finally {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (ms > SLOW_HOOK_MS) logger.atWarning().log("[ModSettingsManager] %s.%s took %d ms on the world thread; a hook should return quickly.", pluginId, HOOK, ms);
            }
        };
        World world = Universe.get() == null ? null : Universe.get().getDefaultWorld();
        if (world == null || world.isInThread()) call.run();
        else world.execute(call);
        // A world that stops before running the call must not leave the window waiting.
        return f.completeOnTimeout(HookOutcome.FAILED, 10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void tellEditors(String message) {
        Universe u = Universe.get();
        if (u == null) return;
        Message m = Message.raw(message);
        for (PlayerRef pr : u.getPlayers()) {
            if (canEdit(pr.getUuid())) pr.sendMessage(m);
        }
    }

    @Override
    public void info(String message) {
        logger.atInfo().log("%s", message);
    }

    @Override
    public void warn(String message) {
        logger.atWarning().log("%s", message);
    }
}
