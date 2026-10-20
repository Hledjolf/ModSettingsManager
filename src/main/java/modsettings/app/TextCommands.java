package modsettings.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import modsettings.json.Json;
import modsettings.model.Control;
import modsettings.model.ControlInference;

/**
 * The text side of {@code /modsettings} (alias {@code /msm}), for the server console and for players who
 * prefer typing: {@code list}, {@code show <mod>}, {@code set <mod> <key> <value>}, {@code reload}. With no
 * arguments a player gets the window instead (handled by the adapter).
 */
public final class TextCommands {
    private final SettingsController controller;
    private final Runnable rediscover;

    public TextCommands(SettingsController controller, Runnable rediscover) {
        this.controller = controller;
        this.rediscover = rediscover;
    }

    /** The command's arguments after the command name, split on whitespace. */
    public static List<String> split(String input, List<String> names) {
        List<String> out = new ArrayList<>();
        if (input == null) return out;
        String s = input.strip();
        if (s.startsWith("/")) s = s.substring(1);
        for (String t : s.split("\\s+")) if (!t.isEmpty()) out.add(t);
        if (!out.isEmpty() && names.contains(out.get(0).toLowerCase(Locale.ROOT))) out.remove(0);
        return out;
    }

    public static List<String> usage() {
        return List.of(
            "/modsettings  opens the settings window (alias /msm)",
            "/modsettings <mod>  opens it on one mod",
            "/modsettings list  lists the mods with settings",
            "/modsettings show <mod>  prints a mod's settings",
            "/modsettings set <mod> <key> <value>  changes one setting",
            "/modsettings reload  looks for mods, configs and sidecars again");
    }

    public CompletableFuture<List<String>> run(Actor actor, List<String> args) {
        if (args.isEmpty() || args.get(0).equalsIgnoreCase("help")) return done(usage());
        String sub = args.get(0).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                List<String> out = new ArrayList<>();
                List<ModEntry> mods = controller.mods();
                out.add(mods.size() + (mods.size() == 1 ? " mod" : " mods") + " with settings:");
                for (ModEntry m : mods) {
                    String tags = (m.hasSidecar() ? " [sidecar]" : m.sidecarRejected() ? " [sidecar rejected]" : "") + (m.hasHook() ? " [live]" : "")
                        + (controller.restartPendingKeys(m.id()).isEmpty() ? "" : " [restart needed]");
                    out.add("  " + m.id() + (m.version() == null ? "" : " " + m.version()) + tags);
                }
                return done(out);
            }
            case "show" -> {
                if (args.size() < 2) return done(List.of("Usage: /modsettings show <mod>"));
                Optional<ModEntry> m = controller.mod(args.get(1));
                if (m.isEmpty()) return done(List.of(notFound(args.get(1))));
                ModView v = controller.view(m.get());
                List<String> out = new ArrayList<>();
                out.add(m.get().id() + "  " + m.get().configFile());
                if (v.error() != null) out.add("  " + v.error());
                for (ControlInference.Group g : v.groups()) {
                    if (g.title() != null) out.add("[" + g.title() + "]");
                    for (Control c : g.controls()) {
                        String hint = c.hint();
                        out.add("  " + c.key() + " = " + Json.write(c.value()) + "  (" + c.kind().name().toLowerCase(Locale.ROOT)
                            + (hint.isEmpty() ? "" : ", " + hint) + (c.options() == null ? "" : ", one of " + c.options()) + ")");
                    }
                }
                List<String> pending = controller.restartPendingKeys(m.get().id());
                if (!pending.isEmpty()) out.add("Restart needed for: " + String.join(", ", pending));
                return done(out);
            }
            case "set" -> {
                if (args.size() < 4) return done(List.of("Usage: /modsettings set <mod> <key> <value>"));
                Optional<ModEntry> m = controller.mod(args.get(1));
                if (m.isEmpty()) return done(List.of(notFound(args.get(1))));
                String value = String.join(" ", args.subList(3, args.size()));
                return controller.set(actor, m.get(), args.get(2), value).thenApply(r -> {
                    List<String> out = new ArrayList<>();
                    out.add(r.message());
                    if (r.errors().size() > 1) for (String e : r.errors()) out.add("  " + e);
                    if (!r.restartKeys().isEmpty()) out.add("Restart needed for: " + String.join(", ", r.restartKeys()));
                    return out;
                });
            }
            case "reload" -> {
                if (!controller.canEdit(actor)) return done(List.of("Only operators, or players with the " + SettingsController.PERMISSION + " permission, can reload."));
                rediscover.run();
                return done(List.of("Found " + controller.mods().size() + " mods with settings."));
            }
            default -> {
                return null; // not a subcommand: the caller treats it as a mod name for the window
            }
        }
    }

    private String notFound(String q) {
        return "No mod with settings matches \"" + q + "\". Try /modsettings list.";
    }

    private static CompletableFuture<List<String>> done(List<String> lines) {
        return CompletableFuture.completedFuture(lines);
    }
}
