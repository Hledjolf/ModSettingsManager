package modsettings.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import modsettings.json.Json;

/**
 * Remembers each value Mod Settings Manager wrote ({@code written.json} in its data folder), so that after
 * the next restart it can tell whether a mod put a value back, for example by saving its in-memory config
 * on shutdown. Entries from the current boot wait; entries from an earlier boot are checked once and dropped.
 */
public final class WriteLedger {
    public record Entry(String plugin, String file, String key, Object value, String boot, String time) {}

    /** A value a mod put back: what was written and what the file holds now. */
    public record Revert(String plugin, String file, String key, Object wrote, Object now, boolean missing) {}

    private final Path store;
    private final List<Entry> entries = new ArrayList<>();

    public WriteLedger(Path store) {
        this.store = store;
    }

    /** Loads the ledger; an unreadable file is renamed aside and the ledger starts empty. Returns a problem or null. */
    @SuppressWarnings("unchecked")
    public synchronized String load() {
        entries.clear();
        if (!Files.exists(store)) return null;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(store, StandardCharsets.UTF_8));
            if (root.get("entries") instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> raw)) continue;
                    Map<String, Object> m = (Map<String, Object>) raw;
                    entries.add(new Entry(str(m, "plugin"), str(m, "file"), str(m, "key"), m.get("value"), str(m, "boot"), str(m, "time")));
                }
            }
            return null;
        } catch (IOException | RuntimeException e) {
            try {
                Files.move(store, store.resolveSibling(store.getFileName() + ".broken"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nothing else to do
            }
            return store + " was unreadable (" + e.getMessage() + "); it was renamed to " + store.getFileName() + ".broken";
        }
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    public synchronized void record(String boot, String time, String plugin, Path file, String key, Object value) throws IOException {
        String f = file.toAbsolutePath().normalize().toString();
        entries.removeIf(e -> e.plugin().equals(plugin) && e.file().equals(f) && e.key().equals(key));
        entries.add(new Entry(plugin, f, key, value, boot, time));
        save();
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Checks every entry written before this boot against its file now, drops those entries, and returns
     * the values that changed. {@code reader} decodes a config file, or returns empty if it can't be read.
     */
    public synchronized List<Revert> checkAfterRestart(String currentBoot, java.util.function.Function<Path, Optional<Map<String, Object>>> reader) throws IOException {
        List<Revert> out = new ArrayList<>();
        List<Entry> keep = new ArrayList<>();
        Map<String, Optional<Map<String, Object>>> cache = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (currentBoot.equals(e.boot())) {
                keep.add(e);
                continue;
            }
            Optional<Map<String, Object>> cfg = cache.computeIfAbsent(e.file(), f -> reader.apply(Path.of(f)));
            if (cfg.isEmpty()) {
                out.add(new Revert(e.plugin(), e.file(), e.key(), e.value(), null, true));
                continue;
            }
            boolean present = KeyPath.has(cfg.get(), e.key());
            Object now = KeyPath.get(cfg.get(), e.key()).orElse(null);
            if (!present || !Values.same(null, e.value(), now)) out.add(new Revert(e.plugin(), e.file(), e.key(), e.value(), now, !present));
        }
        boolean changed = keep.size() != entries.size();
        entries.clear();
        entries.addAll(keep);
        if (changed) save();
        return out;
    }

    private void save() throws IOException {
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plugin", e.plugin());
            m.put("file", e.file());
            m.put("key", e.key());
            m.put("value", e.value());
            m.put("boot", e.boot());
            m.put("time", e.time());
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", 1L);
        root.put("entries", list);
        ConfigFile.write(store, root, "  ");
        Files.deleteIfExists(ConfigFile.backupOf(store));
    }
}
