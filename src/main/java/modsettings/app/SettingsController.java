package modsettings.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import modsettings.json.Json;
import modsettings.model.ChangeLog;
import modsettings.model.Changes;
import modsettings.model.ConfigFile;
import modsettings.model.Control;
import modsettings.model.ControlInference;
import modsettings.model.Sidecar;
import modsettings.model.SidecarParser;
import modsettings.model.Values;
import modsettings.model.WriteLedger;

/**
 * The heart of Mod Settings Manager, with no Hytale types: discovers each plugin's config and sidecar,
 * builds views for the window, validates and writes changes, calls the live-apply hook and keeps the
 * restart and revert state. Both the window and the {@code /modsettings} console commands go through it.
 */
public final class SettingsController {
    public static final String PERMISSION = "modsettings.edit";
    public static final String SELF_ID = "ModSettingsManager:ModSettingsManager";

    private final Platform platform;
    private final SidecarParser parser;
    private final ChangeLog changeLog;
    private final WriteLedger ledger;
    private final Clock clock;
    private final String bootId = UUID.randomUUID().toString();

    private volatile List<ModEntry> mods = List.of();
    private volatile boolean announce;
    /** plugin id -> key -> value waiting for a restart. */
    private final Map<String, Map<String, Object>> restartPending = new ConcurrentHashMap<>();
    /** plugin id -> values a mod put back after the last restart. */
    private final Map<String, List<WriteLedger.Revert>> reverts = new ConcurrentHashMap<>();

    public SettingsController(Platform platform, Path dataDir, SidecarParser parser, Clock clock) {
        this.platform = platform;
        this.parser = parser;
        this.changeLog = new ChangeLog(dataDir.resolve("changes.log"));
        this.ledger = new WriteLedger(dataDir.resolve("written.json"));
        this.clock = clock;
    }

    /** Whether applied changes are announced to online editors in chat. */
    public void setAnnounce(boolean announce) {
        this.announce = announce;
    }

    public Path changeLogFile() {
        return changeLog.file();
    }

    // ---- discovery ------------------------------------------------------------------------------------

    /** Finds every plugin with a config file (or a sidecar). {@code hidden}: plugin ids to leave out. */
    public synchronized void discover(Set<String> hidden) {
        Set<String> hide = new LinkedHashSet<>();
        for (String h : hidden) hide.add(h.toLowerCase(Locale.ROOT));
        List<ModEntry> found = new ArrayList<>();
        for (Platform.PluginInfo p : platform.plugins()) {
            if (hide.contains(p.id().toLowerCase(Locale.ROOT))) continue;
            ModEntry e = entryFor(p);
            if (e != null) found.add(e);
        }
        found.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        mods = List.copyOf(found);
        long withSidecar = found.stream().filter(ModEntry::hasSidecar).count();
        long rejected = found.stream().filter(ModEntry::sidecarRejected).count();
        platform.info(String.format("[ModSettingsManager] %d plugins with settings (%d with a sidecar, %d sidecars rejected)", found.size(), withSidecar, rejected));
    }

    ModEntry entryFor(Platform.PluginInfo p) {
        String stem = p.fileStem() + ".settings.json";
        String text = null, source = null;
        Path dataDir = p.dataDir();
        if (dataDir != null) {
            for (Path candidate : List.of(dataDir.resolve("Settings").resolve(stem), dataDir.resolve(stem))) {
                if (Files.isRegularFile(candidate)) {
                    try {
                        text = Files.readString(candidate, StandardCharsets.UTF_8);
                        source = candidate.toString();
                        break;
                    } catch (IOException e) {
                        platform.warn("[ModSettingsManager] cannot read sidecar " + candidate + ": " + e.getMessage());
                    }
                }
            }
        }
        if (text == null && p.bundledSidecar() != null) {
            text = p.bundledSidecar();
            source = p.bundledSidecarSource();
        }

        Sidecar sidecar = Sidecar.none(p.id());
        List<String> errors = List.of(), warnings = List.of();
        if (text != null) {
            SidecarParser.Result r = parser.parse(text, p.id());
            warnings = r.warnings();
            if (r.ok()) {
                sidecar = r.sidecar();
                for (String w : warnings) platform.warn("[ModSettingsManager] sidecar " + source + ": " + w);
            } else {
                errors = r.errors();
                platform.warn("[ModSettingsManager] Rejected sidecar " + source + " for " + p.id() + ": " + String.join("; ", errors)
                    + ". Its settings are shown with inferred controls instead.");
            }
        }
        if (dataDir == null) return null;
        Path config = dataDir.resolve(sidecar.config()).normalize();
        if (!config.startsWith(dataDir.normalize())) {
            platform.warn("[ModSettingsManager] sidecar " + source + ": config path " + sidecar.config() + " leaves the plugin's data folder; ignored");
            config = dataDir.resolve(Sidecar.DEFAULT_CONFIG);
        }
        boolean exists = Files.isRegularFile(config);
        if (!exists && (source == null || !errors.isEmpty())) return null;

        StringBuilder search = new StringBuilder(p.id()).append(' ').append(p.name()).append(' ');
        if (exists) {
            try {
                Map<String, Object> cfg = ConfigFile.read(config).config();
                for (Control c : ControlInference.controls(cfg, sidecar)) search.append(c.key()).append(' ').append(c.label()).append(' ');
            } catch (IOException | RuntimeException ignored) {
                // shown as an error when opened
            }
        }
        for (var k : sidecar.keys().entrySet()) {
            search.append(k.getKey()).append(' ');
            if (k.getValue().label() != null) search.append(k.getValue().label()).append(' ');
        }
        return new ModEntry(p.id(), p.name(), p.group(), p.version(), p.description(), dataDir, config, sidecar, source,
            errors, warnings, platform.hasHook(p.id()), search.toString().toLowerCase(Locale.ROOT));
    }

    public List<ModEntry> mods() {
        return mods;
    }

    public List<ModEntry> search(String query) {
        return mods.stream().filter(m -> m.matches(query)).toList();
    }

    /** A mod by id ({@code Group:Name}) or by name, ignoring case; a unique prefix of either works too. */
    public Optional<ModEntry> mod(String idOrName) {
        if (idOrName == null) return Optional.empty();
        String q = idOrName.toLowerCase(Locale.ROOT);
        for (ModEntry m : mods) if (m.id().equalsIgnoreCase(q) || m.name().equalsIgnoreCase(q)) return Optional.of(m);
        List<ModEntry> prefix = mods.stream().filter(m -> m.id().toLowerCase(Locale.ROOT).startsWith(q) || m.name().toLowerCase(Locale.ROOT).startsWith(q)).toList();
        return prefix.size() == 1 ? Optional.of(prefix.get(0)) : Optional.empty();
    }

    // ---- views ----------------------------------------------------------------------------------------

    /** Reads the mod's config now and lays out its controls. */
    public ModView view(ModEntry mod) {
        if (!Files.isRegularFile(mod.configFile())) {
            return new ModView(mod, Map.of(), "  ", List.of(), List.of(),
                "No config file yet at " + mod.configFile() + ". Start the server once so the mod writes it.");
        }
        try {
            ConfigFile.Read r = ConfigFile.read(mod.configFile());
            List<ControlInference.Group> groups = ControlInference.layout(r.config(), mod.sidecar());
            List<Control> flat = new ArrayList<>();
            for (var g : groups) flat.addAll(g.controls());
            return new ModView(mod, r.config(), r.indent(), groups, flat, null);
        } catch (IOException e) {
            return new ModView(mod, Map.of(), "  ", List.of(), List.of(), "Cannot read " + mod.configFile() + ": " + e.getMessage());
        } catch (Json.ParseException e) {
            return new ModView(mod, Map.of(), "  ", List.of(), List.of(), mod.configFile().getFileName() + " is not valid JSON: " + e.getMessage());
        }
    }

    // ---- permissions ----------------------------------------------------------------------------------

    public boolean canEdit(Actor actor) {
        return actor.console() || platform.canEdit(actor.uuid());
    }

    // ---- applying -------------------------------------------------------------------------------------

    /**
     * Validates {@code raw} (key to the value a control or command sent) against the controls the player saw,
     * writes the changed keys into the file as it is now, logs them, and calls the mod's hook. Never completes
     * exceptionally.
     */
    public CompletableFuture<ApplyResult> apply(Actor actor, ModView seen, Map<String, Object> raw) {
        ModEntry mod = seen.mod();
        if (!canEdit(actor)) {
            platform.warn("[ModSettingsManager] refused a settings change to " + mod.id() + " by " + actor.describe() + ": not an operator and no " + PERMISSION + " permission");
            return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.REFUSED,
                "Only operators, or players with the " + PERMISSION + " permission, can change settings."));
        }
        if (seen.error() != null) return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.FAILED, seen.error()));

        Map<String, Object> parsed = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (var e : raw.entrySet()) {
            Control c = seen.control(e.getKey());
            if (c == null || !c.editable()) continue;
            Values.Parsed p = Values.parse(c, e.getValue());
            if (p.ok()) parsed.put(c.key(), p.value());
            else errors.add(p.error());
        }
        if (!errors.isEmpty()) {
            return CompletableFuture.completedFuture(new ApplyResult(ApplyResult.Status.INVALID,
                errors.size() == 1 ? errors.get(0) : errors.size() + " values are not valid; nothing was saved.", errors, List.of(), Set.of(), null));
        }
        List<Changes.Change> wanted = Changes.diff(seen.controls(), parsed);
        if (wanted.isEmpty()) return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.NOTHING, "Nothing to apply: no value differs from the file."));

        List<Changes.Change> applied = new ArrayList<>();
        synchronized (this) {
            ConfigFile.Read now;
            try {
                now = Files.isRegularFile(mod.configFile()) ? ConfigFile.read(mod.configFile()) : new ConfigFile.Read(new LinkedHashMap<>(), seen.indent());
            } catch (IOException | RuntimeException e) {
                return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.FAILED, "Cannot read " + mod.configFile() + " now: " + e.getMessage()));
            }
            for (Changes.Change ch : wanted) {
                Object old = modsettings.model.KeyPath.get(now.config(), ch.key()).orElse(null);
                applied.add(new Changes.Change(ch.key(), old, ch.newValue()));
            }
            try {
                Changes.applyTo(now.config(), applied);
                ConfigFile.write(mod.configFile(), now.config(), now.indent());
            } catch (IOException | RuntimeException e) {
                platform.warn("[ModSettingsManager] could not write " + mod.configFile() + ": " + e);
                return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.FAILED, "Could not write " + mod.configFile().getFileName() + ": " + e.getMessage()));
            }
            Instant t = clock.instant();
            try {
                changeLog.append(t, actor.describe(), mod.id(), mod.configFile().toString(), applied);
            } catch (IOException e) {
                platform.warn("[ModSettingsManager] could not append to " + changeLog.file() + ": " + e.getMessage());
            }
            for (Changes.Change ch : applied) {
                try {
                    ledger.record(bootId, t.toString(), mod.id(), mod.configFile(), ch.key(), ch.newValue());
                } catch (IOException e) {
                    platform.warn("[ModSettingsManager] could not update " + "written.json: " + e.getMessage());
                }
            }
        }

        Map<String, Object> arg = Collections.unmodifiableMap(Changes.hookArgument(applied));
        CompletableFuture<Changes.HookOutcome> hook = mod.hasHook()
            ? platform.callHook(mod.id(), arg).exceptionally(t -> Changes.HookOutcome.FAILED)
            : CompletableFuture.completedFuture(Changes.HookOutcome.MISSING);
        return hook.thenApply(outcome -> {
            Set<String> restart = Changes.restartKeys(applied, mod.sidecar(), outcome);
            Map<String, Object> pending = restartPending.computeIfAbsent(mod.id(), k -> Collections.synchronizedMap(new LinkedHashMap<>()));
            for (Changes.Change ch : applied) {
                if (restart.contains(ch.key())) pending.put(ch.key(), ch.newValue());
                else pending.remove(ch.key());
            }
            List<String> live = applied.stream().map(Changes.Change::key).filter(k -> !restart.contains(k)).toList();
            platform.info("[ModSettingsManager] " + actor.describe() + " changed " + mod.id() + ": "
                + String.join(", ", applied.stream().map(c -> c.key() + "=" + Json.write(c.newValue())).toList())
                + " (hook " + outcome.name().toLowerCase(Locale.ROOT) + "; live: " + (live.isEmpty() ? "none" : String.join(", ", live))
                + "; after restart: " + (restart.isEmpty() ? "none" : String.join(", ", restart)) + ")");
            if (announce) {
                platform.tellEditors("[Mod Settings] " + actor.name() + " changed " + mod.name() + ": "
                    + String.join(", ", applied.stream().map(c -> c.key() + " = " + Values.display(c.newValue())).toList())
                    + (restart.isEmpty() ? "" : " (restart needed)"));
            }
            String msg = "Saved " + applied.size() + (applied.size() == 1 ? " change" : " changes") + " to " + mod.configFile().getFileName() + ". "
                + (restart.isEmpty() ? "All in effect now." : restart.size() == applied.size() ? "They take effect after a restart." : restart.size() + " take effect after a restart.");
            return new ApplyResult(ApplyResult.Status.WRITTEN, msg, List.of(), List.copyOf(applied), Set.copyOf(restart), outcome);
        });
    }

    /** {@code /modsettings set}: one key from typed text. */
    public CompletableFuture<ApplyResult> set(Actor actor, ModEntry mod, String key, String text) {
        ModView v = view(mod);
        if (v.error() == null && v.control(key) == null) {
            return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.INVALID, "No editable setting " + key + " in " + mod.name() + "."));
        }
        Control c = v.control(key);
        if (c != null && !c.editable()) {
            return CompletableFuture.completedFuture(ApplyResult.of(ApplyResult.Status.INVALID, key + " is read-only here (" + c.hint() + ")."));
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(key, text);
        return apply(actor, v, raw);
    }

    // ---- restart state --------------------------------------------------------------------------------

    /** Keys of the mod changed this session that wait for a restart, with their new values. */
    public Map<String, Object> restartPending(String modId) {
        Map<String, Object> m = restartPending.get(modId);
        if (m == null) return Map.of();
        synchronized (m) {
            return Map.copyOf(new LinkedHashMap<>(m));
        }
    }

    /** Ordered keys waiting for a restart (for display). */
    public List<String> restartPendingKeys(String modId) {
        Map<String, Object> m = restartPending.get(modId);
        if (m == null) return List.of();
        synchronized (m) {
            return List.copyOf(m.keySet());
        }
    }

    public boolean anyRestartPending() {
        return restartPending.values().stream().anyMatch(m -> !m.isEmpty());
    }

    public List<WriteLedger.Revert> reverts(String modId) {
        return reverts.getOrDefault(modId, List.of());
    }

    public Map<String, List<WriteLedger.Revert>> allReverts() {
        return Map.copyOf(reverts);
    }

    /**
     * At boot: re-reads every file written before the last restart and warns about each value a mod put
     * back. Run it after all plugins have started.
     */
    public List<WriteLedger.Revert> checkAfterRestart() {
        String problem = ledger.load();
        if (problem != null) platform.warn("[ModSettingsManager] " + problem);
        List<WriteLedger.Revert> found;
        try {
            found = ledger.checkAfterRestart(bootId, path -> {
                try {
                    return Optional.of(ConfigFile.read(path).config());
                } catch (IOException | RuntimeException e) {
                    return Optional.empty();
                }
            });
        } catch (IOException e) {
            platform.warn("[ModSettingsManager] could not update written.json: " + e.getMessage());
            return List.of();
        }
        reverts.clear();
        for (WriteLedger.Revert r : found) {
            reverts.computeIfAbsent(r.plugin(), k -> new ArrayList<>()).add(r);
            platform.warn("[ModSettingsManager] After the restart, " + r.plugin() + " has " + r.key() + " = "
                + (r.missing() ? "(missing)" : Json.write(r.now())) + " in " + r.file() + ", but Mod Settings Manager wrote " + Json.write(r.wrote())
                + ". The mod probably saves its config when the server stops; change this setting while the server is stopped.");
        }
        return found;
    }

    public String bootId() {
        return bootId;
    }
}
