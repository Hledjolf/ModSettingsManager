package modsettings.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Change diffing and the restart decision. */
public final class Changes {
    private Changes() {}

    /** One changed key: the value the player saw and the value they chose. */
    public record Change(String key, Object oldValue, Object newValue) {}

    /** What the mod's {@code onSettingsChanged} hook said. */
    public enum HookOutcome {
        /** The plugin class has no such method. */
        MISSING,
        /** It returned true: every change took effect live. */
        LIVE,
        /** It returned false: restart needed. */
        RESTART,
        /** It threw: the mod rejected the values. They are saved, but a restart will not apply them. */
        FAILED
    }

    /**
     * The changes from {@code draft} (key to parsed value) against the values the controls show. Keys whose
     * draft equals the shown value (see {@link Values#same}) are not changes. Order follows {@code controls}.
     */
    public static List<Change> diff(List<Control> controls, Map<String, Object> draft) {
        List<Change> out = new ArrayList<>();
        for (Control c : controls) {
            if (!c.editable() || !draft.containsKey(c.key())) continue;
            Object v = draft.get(c.key());
            if (!Values.same(c, c.value(), v)) out.add(new Change(c.key(), c.inFile() ? c.value() : null, v));
        }
        return out;
    }

    /** Writes the changes into a decoded config (in place). Existing keys keep their position. */
    public static void applyTo(Map<String, Object> config, List<Change> changes) {
        for (Change ch : changes) KeyPath.set(config, ch.key(), ch.newValue());
    }

    /** The hook's argument: changed key to new value, in order. */
    public static Map<String, Object> hookArgument(List<Change> changes) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Change ch : changes) m.put(ch.key(), ch.newValue());
        return m;
    }

    /**
     * Which of the changed keys need a restart (contract v1, "Live apply"):
     * <ul>
     *   <li>a key whose sidecar entry says {@code "restart": true} always does;</li>
     *   <li>no hook: every key except those with {@code "restart": false} does;</li>
     *   <li>the hook returned true: no other key does;</li>
     *   <li>the hook returned false: every changed key does;</li>
     *   <li>the hook threw: only keys with {@code "restart": true} do. The rest are "could not apply" (see
     *       {@link #failedKeys}), since a restart does not fix a value the mod rejected.</li>
     * </ul>
     */
    public static Set<String> restartKeys(List<Change> changes, Sidecar sidecar, HookOutcome outcome) {
        Set<String> out = new LinkedHashSet<>();
        for (Change ch : changes) {
            Boolean flag = sidecar.key(ch.key()).restart();
            boolean restart = switch (outcome) {
                case MISSING -> !Boolean.FALSE.equals(flag);
                case LIVE -> Boolean.TRUE.equals(flag);
                case RESTART -> true;
                case FAILED -> Boolean.TRUE.equals(flag);
            };
            if (restart) out.add(ch.key());
        }
        return out;
    }

    /** The changed keys a throwing hook could not apply: every key that is not already waiting for a restart. */
    public static Set<String> failedKeys(List<Change> changes, Set<String> restartKeys, HookOutcome outcome) {
        Set<String> out = new LinkedHashSet<>();
        if (outcome != HookOutcome.FAILED) return out;
        for (Change ch : changes) if (!restartKeys.contains(ch.key())) out.add(ch.key());
        return out;
    }

    /** "1 change takes effect" / "2 changes take effect" (the caller adds the rest of the sentence). */
    public static String takeEffect(int n) {
        return n + (n == 1 ? " change takes" : " changes take") + " effect";
    }
}
