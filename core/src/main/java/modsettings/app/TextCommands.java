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

    /** The characters a backslash escapes in a command line, as in Hytale's tokenizer. */
    private static final String ESCAPABLE = "\"',[]\\";

    /**
     * The command's arguments after the command name, from the line as typed (before the game's own
     * tokenizer has unescaped it). Whitespace separates arguments; quotes around a whole argument group it
     * and are removed; a backslash makes the next {@code " ' , [ ] \} literal. The value of {@code set} is
     * the rest of the line, so its spaces, commas and brackets arrive as typed.
     */
    public static List<String> split(String input, List<String> names) {
        List<String> out = new ArrayList<>();
        if (input == null) return out;
        String s = input.strip();
        if (s.startsWith("/")) s = s.substring(1);
        List<Integer> starts = new ArrayList<>();
        List<String> tokens = tokens(s, starts);
        int first = !tokens.isEmpty() && names.contains(unquote(tokens.get(0)).toLowerCase(Locale.ROOT)) ? 1 : 0;
        for (int k = first; k < tokens.size(); k++) {
            if (out.size() == 3 && out.get(0).equalsIgnoreCase("set")) {
                out.add(unquote(s.substring(starts.get(k))));
                break;
            }
            out.add(unquote(tokens.get(k)));
        }
        return out;
    }

    /** Splits at whitespace outside quotes, skipping escaped characters; records where each token starts. */
    private static List<String> tokens(String s, List<Integer> starts) {
        List<String> out = new ArrayList<>();
        int start = -1;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (start < 0) {
                if (Character.isWhitespace(c)) continue;
                start = i;
            }
            if (c == '\\') {
                i++;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || (c == '\'' && i == start)) {
                // As in Hytale: a double quote opens anywhere, an apostrophe only at the start (so "it's" is a word).
                quote = c;
            } else if (Character.isWhitespace(c)) {
                out.add(s.substring(start, i));
                starts.add(start);
                start = -1;
            }
        }
        if (start >= 0) {
            out.add(s.substring(start));
            starts.add(start);
        }
        return out;
    }

    /** One argument as meant: quotes around all of it removed, and each escape replaced by its character. */
    static String unquote(String t) {
        int from = 0, to = t.length();
        if (to >= 2 && (t.charAt(0) == '"' || t.charAt(0) == '\'') && closingQuote(t) == to - 1) {
            from = 1;
            to--;
        }
        StringBuilder b = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < to && ESCAPABLE.indexOf(t.charAt(i + 1)) >= 0) c = t.charAt(++i);
            b.append(c);
        }
        return b.toString();
    }

    private static int closingQuote(String t) {
        char q = t.charAt(0);
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\') i++;
            else if (c == q) return i;
        }
        return -1;
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
                        + (controller.restartPendingKeys(m.id()).isEmpty() ? "" : " [restart needed]")
                        + (controller.couldNotApplyKeys(m.id()).isEmpty() ? "" : " [could not apply]")
                        + (controller.reverts(m.id()).isEmpty() ? "" : " [put back]");
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
                List<String> rejected = controller.couldNotApplyKeys(m.get().id());
                if (!rejected.isEmpty()) out.add("Could not apply (saved, but the mod rejected them; see the server log): " + String.join(", ", rejected));
                var reverts = controller.reverts(m.get().id());
                if (!reverts.isEmpty()) {
                    out.add("Put back: after the last restart this mod put back " + String.join(", ", reverts.stream().map(r -> r.describe()).toList())
                        + ". It probably saves its config when the server stops: change these while the server is stopped.");
                }
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
                    if (!r.failedKeys().isEmpty()) out.add("Could not apply: " + String.join(", ", r.failedKeys()));
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
