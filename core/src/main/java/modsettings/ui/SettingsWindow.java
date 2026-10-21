package modsettings.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import modsettings.app.Actor;
import modsettings.app.ApplyResult;
import modsettings.app.ModEntry;
import modsettings.app.ModView;
import modsettings.app.SettingsController;
import modsettings.json.Json;
import modsettings.model.Control;
import modsettings.model.ControlInference;
import modsettings.model.Steps;
import modsettings.model.Values;
import modsettings.model.WriteLedger;

/**
 * One player's settings window, as pure state plus rendering into {@link UiOps}. Mods are listed on the left
 * (filtered by the search box), the selected mod's sections and controls on the right, with Apply, Revert
 * and Reset to default at the bottom.
 *
 * <p>Each control sends its value on change ({@code edit}), which marks the row as changed. Apply also
 * captures every control's current value in its own event, so an edit event dropped by the client's
 * acknowledgement gate never loses a value. Every re-render of the right side bumps a generation number;
 * events from an older render are ignored.
 */
public final class SettingsWindow {
    public static final String DIR = "ModSettings/";
    public static final String DOC = DIR + "Window.ui";
    public static final String MOD_ROW = DIR + "ModRow.ui";
    public static final String SECTION_ROW = DIR + "SectionRow.ui";
    public static final String NOTE_ROW = DIR + "NoteRow.ui";
    public static final String BOOL_ROW = DIR + "BoolRow.ui";
    public static final String INT_ROW = DIR + "IntRow.ui";
    public static final String FLOAT_ROW = DIR + "FloatRow.ui";
    public static final String TEXT_ROW = DIR + "TextRow.ui";
    public static final String ENUM_ROW = DIR + "EnumRow.ui";
    public static final String READONLY_ROW = DIR + "ReadOnlyRow.ui";
    public static final String FOOTER = DIR + "Footer.ui";

    /** Every document the window uses (the adapter checks they are registered). */
    public static final List<String> DOCUMENTS = List.of(DOC, MOD_ROW, SECTION_ROW, NOTE_ROW, BOOL_ROW, INT_ROW, FLOAT_ROW, TEXT_ROW, ENUM_ROW, READONLY_ROW, FOOTER);

    static final int MAX_DISPLAY = 160;

    private final SettingsController controller;
    private final Actor actor;
    private final boolean canEdit;
    private String query = "";
    private ModEntry selected;
    private ModView view;
    private final Map<String, Object> draft = new LinkedHashMap<>();
    /** Row index of each editable control in the current render. */
    private final Map<String, Integer> rowOf = new LinkedHashMap<>();
    private final java.util.Set<String> markedChanged = new HashSet<>();
    /** Stepper buttons shown disabled, as "key-" / "key+". */
    private final java.util.Set<String> stepDisabled = new HashSet<>();
    private int gen;
    private String status = "";

    public SettingsWindow(SettingsController controller, Actor actor, String initialMod) {
        this.controller = controller;
        this.actor = actor;
        this.canEdit = controller.canEdit(actor);
        Optional<ModEntry> first = initialMod != null ? controller.mod(initialMod) : Optional.empty();
        this.selected = first.orElse(controller.mods().isEmpty() ? null : controller.mods().get(0));
        if (!canEdit) status = "You can look at these settings. Changing them needs operator status or the " + SettingsController.PERMISSION + " permission.";
    }

    public boolean canEdit() {
        return canEdit;
    }

    public ModEntry selected() {
        return selected;
    }

    public ModView view() {
        return view;
    }

    public Map<String, Object> draft() {
        return Map.copyOf(draft);
    }

    public int generation() {
        return gen;
    }

    public String status() {
        return status;
    }

    /** Everything after the main document is appended: static bindings plus both panels. */
    public synchronized UiOps open() {
        UiOps ops = new UiOps();
        ops.set("#Search.Value", query);
        ops.bind("#Search", "ValueChanged", Map.of("Action", "search", "@Query", "#Search.Value"));
        renderModList(ops);
        renderDetail(ops, true);
        return ops;
    }

    /** Handles one event from the page. The returned ops are sent as a partial update (possibly none). */
    public synchronized CompletableFuture<UiOps> onEvent(String action, Map<String, Object> data) {
        UiOps ops = new UiOps();
        if (action == null) return CompletableFuture.completedFuture(ops);
        switch (action) {
            case "search" -> {
                query = str(data.get("@Query"));
                renderModList(ops);
            }
            case "select" -> {
                Optional<ModEntry> m = controller.mod(str(data.get("Mod")));
                if (m.isPresent()) {
                    selected = m.get();
                    draft.clear();
                    status = canEdit ? "" : status;
                    renderModList(ops);
                    renderDetail(ops, true);
                }
            }
            case "edit" -> onEdit(ops, data);
            case "step" -> onStep(ops, data);
            case "revert" -> {
                if (!sameGen(data)) break;
                draft.clear();
                status = "Reverted to the values in the file.";
                renderDetail(ops, true);
            }
            case "reset" -> {
                if (!sameGen(data) || view == null) break;
                int n = 0;
                for (Control c : view.controls()) {
                    if (c.hasDefault()) {
                        draft.put(c.key(), c.defaultValue());
                        if (!Values.same(c, c.value(), c.defaultValue())) n++;
                    }
                }
                status = n == 0 ? "Every setting already has its default value." : "Defaults filled in for " + n + (n == 1 ? " setting" : " settings") + ". Press Apply to save, or Revert.";
                renderDetail(ops, false);
            }
            case "apply" -> {
                return onApply(data);
            }
            default -> {
                // unknown actions are ignored
            }
        }
        return CompletableFuture.completedFuture(ops);
    }

    private boolean sameGen(Map<String, Object> data) {
        return String.valueOf(gen).equals(str(data.get("Gen")));
    }

    private void onEdit(UiOps ops, Map<String, Object> data) {
        if (!sameGen(data) || view == null) return;
        String key = str(data.get("Key"));
        Control c = view.control(key);
        Integer row = rowOf.get(key);
        if (c == null || row == null || !data.containsKey("@Value")) return;
        Object raw = data.get("@Value");
        Values.Parsed p = Values.parse(c, raw);
        Object v = p.ok() ? p.value() : raw;
        draft.put(key, v);
        if (Steps.applies(c) && v instanceof Number n) stepButtons(ops, row, c, n.doubleValue(), false);
        boolean changed = !Values.same(c, c.value(), v);
        if (changed != markedChanged.contains(key)) {
            if (changed) markedChanged.add(key);
            else markedChanged.remove(key);
            ops.visible("#Rows[" + row + "] #Changed", changed);
            ops.text("#Status", changedSummary());
        }
    }

    /**
     * A -/+ click. The event carries the field's current value, so a value typed just before the click is
     * stepped from. The server sets the field to the result; a click the client dropped (the acknowledgement
     * gate) changes neither side, so field and draft never disagree. One step per acknowledged update.
     */
    private void onStep(UiOps ops, Map<String, Object> data) {
        if (!sameGen(data) || view == null) return;
        String key = str(data.get("Key"));
        Control c = view.control(key);
        Integer row = rowOf.get(key);
        if (c == null || row == null || !Steps.applies(c)) return;
        int dir = "-1".equals(str(data.get("Dir"))) ? -1 : 1;
        Object fromField = data.get("@Value");
        Double typed = number(fromField);
        Object base = typed != null ? typed : draft.containsKey(key) ? draft.get(key) : c.value();
        double current = base instanceof Number n ? n.doubleValue() : 0.0;
        if (!Steps.canStep(c, current, dir) && Steps.clamp(c, current) == current) return;
        double next = Steps.next(c, current, dir);
        Values.Parsed p = Values.parse(c, next);
        if (!p.ok()) return;
        draft.put(key, p.value());
        String sel = "#Rows[" + row + "]";
        ops.set(sel + " #Input.Value", next);
        stepButtons(ops, row, c, next, false);
        boolean changed = !Values.same(c, c.value(), p.value());
        if (changed) markedChanged.add(key);
        else markedChanged.remove(key);
        ops.visible(sel + " #Changed", changed);
        ops.text("#Status", changedSummary());
    }

    /** Sets the -/+ buttons' Disabled state; only changes are sent unless {@code force}. */
    private void stepButtons(UiOps ops, int row, Control c, double value, boolean force) {
        for (int dir : new int[] {-1, 1}) {
            String id = c.key() + (dir < 0 ? "-" : "+");
            boolean disabled = !Steps.canStep(c, value, dir);
            if (force || disabled != stepDisabled.contains(id)) {
                if (disabled) stepDisabled.add(id);
                else stepDisabled.remove(id);
                ops.set("#Rows[" + row + "] " + (dir < 0 ? "#Minus" : "#Plus") + ".Disabled", disabled);
            }
        }
    }

    private String changedSummary() {
        int n = markedChanged.size();
        return n == 0 ? "" : n + (n == 1 ? " unsaved change" : " unsaved changes") + ". Press Apply to save.";
    }

    private CompletableFuture<UiOps> onApply(Map<String, Object> data) {
        if (view == null || selected == null) return CompletableFuture.completedFuture(new UiOps());
        if (!sameGen(data)) {
            UiOps ops = new UiOps();
            status = "The page was redrawn before that click arrived. Press Apply again.";
            ops.text("#Status", status);
            return CompletableFuture.completedFuture(ops);
        }
        Map<String, Object> raw = new LinkedHashMap<>(draft);
        for (var e : rowOf.entrySet()) {
            Object captured = data.get("@V" + e.getValue());
            if (captured != null) raw.put(e.getKey(), captured);
        }
        ModView seen = view;
        return controller.apply(actor, seen, raw).thenApply(r -> {
            synchronized (this) {
                return afterApply(r);
            }
        });
    }

    private UiOps afterApply(ApplyResult r) {
        UiOps ops = new UiOps();
        switch (r.status()) {
            case WRITTEN -> {
                draft.clear();
                status = r.message();
                renderModList(ops);
                renderDetail(ops, true);
            }
            case INVALID -> {
                status = "Not saved. " + String.join(" ", r.errors().isEmpty() ? List.of(r.message()) : r.errors());
                ops.text("#Status", status);
            }
            default -> {
                status = r.message();
                ops.text("#Status", status);
            }
        }
        lastResult = r;
        return ops;
    }

    private ApplyResult lastResult;

    /** The last Apply's result, for scenarios. */
    public ApplyResult lastResult() {
        return lastResult;
    }

    /** The mod list for the current search text (the page sends it once typing pauses). */
    public synchronized UiOps modListOps() {
        UiOps ops = new UiOps();
        renderModList(ops);
        return ops;
    }

    // ---- rendering --------------------------------------------------------------------------------------

    void renderModList(UiOps ops) {
        List<ModEntry> list = controller.search(query);
        ops.clear("#ModList");
        for (int i = 0; i < list.size(); i++) {
            ModEntry m = list.get(i);
            String sel = "#ModList[" + i + "]";
            ops.append("#ModList", MOD_ROW);
            String mark = !controller.restartPendingKeys(m.id()).isEmpty() ? "  (restart)" : !controller.couldNotApplyKeys(m.id()).isEmpty() ? "  (not applied)" : !controller.reverts(m.id()).isEmpty() ? "  (warning)" : "";
            ops.text(sel + " #Button", m.displayName() + mark);
            ops.set(sel + " #Button.Disabled", selected != null && selected.id().equals(m.id()));
            ops.bind(sel + " #Button", "Activating", Map.of("Action", "select", "Mod", m.id()));
        }
        int total = controller.mods().size();
        ops.text("#ModCount", list.size() == total ? total + (total == 1 ? " mod" : " mods") : list.size() + " of " + total + " mods");
    }

    /** Redraws the right side. {@code fresh}: read the file again (after select, apply, revert). */
    void renderDetail(UiOps ops, boolean fresh) {
        gen++;
        rowOf.clear();
        markedChanged.clear();
        stepDisabled.clear();
        ops.clear("#Rows");
        ops.clear("#Footer");
        if (selected == null) {
            view = null;
            ops.text("#ModName", "Mod Settings");
            ops.text("#ModMeta", "No mods with a config file were found.");
            ops.text("#SidecarNote", "");
            ops.visible("#RestartBanner", false);
            ops.visible("#RevertBanner", false);
            ops.text("#Status", status);
            return;
        }
        if (fresh || view == null || !view.mod().id().equals(selected.id())) view = controller.view(selected);
        ops.text("#ModName", selected.displayName());
        ops.text("#ModMeta", selected.id() + (selected.version() == null ? "" : "  v" + selected.version()) + "   " + selected.configFile());
        ops.text("#SidecarNote", sidecarNote(selected));

        List<String> pending = controller.restartPendingKeys(selected.id());
        List<String> rejected = controller.couldNotApplyKeys(selected.id());
        ops.visible("#RestartBanner", !pending.isEmpty() || !rejected.isEmpty());
        StringBuilder banner = new StringBuilder();
        if (!rejected.isEmpty()) banner.append("Saved, but ").append(selected.displayName()).append(" could not apply: ").append(labels(rejected)).append(". See the server log.");
        if (!pending.isEmpty()) {
            if (banner.length() > 0) banner.append(' ');
            banner.append("Restart needed for: ").append(labels(pending)).append(". These changes are saved and take effect after the server restarts.");
        }
        ops.text("#RestartText", banner.toString());

        List<WriteLedger.Revert> reverts = controller.reverts(selected.id());
        ops.visible("#RevertBanner", !reverts.isEmpty());
        ops.text("#RevertText", reverts.isEmpty() ? "" : revertText(reverts));

        int row = 0;
        if (view.error() != null) {
            ops.append("#Rows", NOTE_ROW);
            ops.text("#Rows[" + row + "] #Text", view.error());
            row++;
        } else if (view.controls().isEmpty()) {
            ops.append("#Rows", NOTE_ROW);
            ops.text("#Rows[" + row + "] #Text", "This config has no settings to show.");
            row++;
        }
        for (ControlInference.Group g : view.groups()) {
            if (g.title() != null) {
                ops.append("#Rows", SECTION_ROW);
                ops.text("#Rows[" + row + "] #Title", g.title());
                ops.text("#Rows[" + row + "] #Description", g.description() == null ? "" : g.description());
                row++;
            }
            for (Control c : g.controls()) {
                renderControl(ops, row, c);
                row++;
            }
        }

        ops.append("#Footer", FOOTER);
        Map<String, String> applyData = new LinkedHashMap<>();
        applyData.put("Action", "apply");
        applyData.put("Gen", String.valueOf(gen));
        for (var e : rowOf.entrySet()) applyData.put("@V" + e.getValue(), "#Rows[" + e.getValue() + "] #Input.Value");
        ops.bind("#Footer[0] #ApplyButton", "Activating", applyData);
        ops.bind("#Footer[0] #RevertButton", "Activating", Map.of("Action", "revert", "Gen", String.valueOf(gen)));
        ops.bind("#Footer[0] #ResetButton", "Activating", Map.of("Action", "reset", "Gen", String.valueOf(gen)));
        boolean editable = view.error() == null && !rowOf.isEmpty();
        ops.set("#Footer[0] #ApplyButton.Disabled", !canEdit || !editable);
        ops.set("#Footer[0] #ResetButton.Disabled", !canEdit || view.controls().stream().noneMatch(Control::hasDefault));
        ops.set("#Footer[0] #RevertButton.Disabled", !editable);
        String summary = changedSummary();
        ops.text("#Status", status.isEmpty() ? summary : status);
    }

    private void renderControl(UiOps ops, int row, Control c) {
        String sel = "#Rows[" + row + "]";
        Object shown = draft.containsKey(c.key()) ? draft.get(c.key()) : c.value();
        switch (c.kind()) {
            case BOOL -> {
                ops.append("#Rows", BOOL_ROW);
                ops.set(sel + " #Input.Value", Boolean.TRUE.equals(shown));
            }
            case INT, FLOAT -> {
                ops.append("#Rows", c.kind() == Control.Kind.INT ? INT_ROW : FLOAT_ROW);
                ops.set(sel + " #Input.Value", shown instanceof Number n ? n.doubleValue() : 0.0);
            }
            case ENUM -> {
                ops.append("#Rows", ENUM_ROW);
                List<UiOps.Entry> entries = new ArrayList<>();
                for (String o : c.options()) entries.add(new UiOps.Entry(o, o));
                ops.entries(sel + " #Input", entries);
                ops.set(sel + " #Input.Value", shown == null ? "" : shown.toString());
            }
            case STRING -> {
                ops.append("#Rows", TEXT_ROW);
                ops.set(sel + " #Input.Value", shown == null ? "" : shown.toString());
            }
            case READONLY -> {
                ops.append("#Rows", READONLY_ROW);
                ops.text(sel + " #Value", truncate(Json.write(shown), MAX_DISPLAY));
            }
        }
        ops.text(sel + " #Label", c.label());
        ops.set(sel + " #Label.TooltipText", tooltip(c));
        ops.text(sel + " #Hint", c.hint());
        boolean changed = draft.containsKey(c.key()) && !Values.same(c, c.value(), draft.get(c.key()));
        ops.visible(sel + " #Changed", changed);
        if (changed) markedChanged.add(c.key());
        if (c.editable()) {
            rowOf.put(c.key(), row);
            ops.bind(sel + " #Input", "ValueChanged", Map.of("Action", "edit", "Key", c.key(), "Gen", String.valueOf(gen), "@Value", sel + " #Input.Value"));
        }
        if (Steps.applies(c)) {
            stepButtons(ops, row, c, shown instanceof Number n ? n.doubleValue() : 0.0, true);
            for (String dir : List.of("-1", "1")) {
                ops.bind(sel + (dir.equals("-1") ? " #Minus" : " #Plus"), "Activating",
                    Map.of("Action", "step", "Key", c.key(), "Dir", dir, "Gen", String.valueOf(gen), "@Value", sel + " #Input.Value"));
            }
        }
    }

    static String tooltip(Control c) {
        StringBuilder b = new StringBuilder();
        if (c.description() != null && !c.description().isBlank()) b.append(c.description()).append("\n\n");
        b.append("Key: ").append(c.key());
        if (c.defaultValue() != null) b.append("\nDefault: ").append(Values.display(c.defaultValue()));
        if (c.kind() == Control.Kind.READONLY) b.append("\nValue: ").append(truncate(Json.write(c.value()), 1000));
        if (Boolean.TRUE.equals(c.restart())) b.append("\nTakes effect after a restart.");
        return b.toString();
    }

    private String labels(List<String> keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            Control c = view == null ? null : view.control(k);
            out.add(c == null ? k : c.label());
        }
        return String.join(", ", out);
    }

    private static String revertText(List<WriteLedger.Revert> reverts) {
        StringBuilder b = new StringBuilder("After the last restart this mod put back ");
        for (int i = 0; i < reverts.size(); i++) {
            WriteLedger.Revert r = reverts.get(i);
            if (i > 0) b.append(", ");
            b.append(r.describe());
        }
        return b.append(". It probably saves its config when the server stops: change these while the server is stopped.").toString();
    }

    static String sidecarNote(ModEntry m) {
        if (m.sidecarRejected()) return "Its sidecar was rejected (" + truncate(m.sidecarErrors().get(0), 120) + "; see the server log), so controls are inferred.";
        String live = m.hasHook() ? "Changes apply live where the mod allows." : "Changes take effect after a restart unless marked otherwise.";
        if (m.hasSidecar()) return "Labels from the mod's sidecar. " + live;
        return "No sidecar: controls are inferred from the values. " + live;
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** A number from an event value (range not checked), or null. */
    private static Double number(Object o) {
        if (o instanceof Number n && !(o instanceof Boolean) && !Double.isNaN(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object o) {
        if (o == null) return "";
        if (o instanceof Double d && d == Math.rint(d) && !d.isInfinite()) return Long.toString(d.longValue());
        return o.toString();
    }
}
