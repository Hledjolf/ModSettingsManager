package modsettings.adapter;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.ParserContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import modsettings.app.Actor;
import modsettings.app.SettingsController;
import modsettings.app.TextCommands;
import modsettings.ui.SettingsWindow;

/**
 * {@code /modsettings} (alias {@code /msm}). Anyone may run it and look; changing a setting checks
 * operator status or {@code modsettings.edit} when it is applied. With no arguments (or a mod name) a
 * player gets the window; the subcommands print text and work from the console too.
 */
public final class SettingsCommand extends AbstractAsyncCommand {
    public static final String NAME = "modsettings";
    public static final String ALIAS = "msm";

    private final SettingsController controller;
    private final TextCommands text;
    private final HytaleLogger logger;

    /**
     * The line as typed, for the call in progress. The context only has the tokens joined again, with the
     * escapes already removed and quotes, commas and brackets changed, so a {@code set} value can't be read
     * back from it. {@code acceptCall} runs {@code executeAsync} on the same thread.
     */
    private static final ThreadLocal<String> RAW_INPUT = new ThreadLocal<>();

    public SettingsCommand(SettingsController controller, TextCommands text, HytaleLogger logger) {
        super(NAME, "Opens the Mod Settings Manager window, or lists, shows and sets mod settings");
        this.controller = controller;
        this.text = text;
        this.logger = logger;
        addAliases(ALIAS);
        setAllowsExtraArguments(true);
        requireNoPermission();
    }

    @Override
    public CompletableFuture<Void> acceptCall(CommandSender sender, ParserContext parser, ParseResult result) {
        RAW_INPUT.set(parser.getRawInput());
        try {
            return super.acceptCall(sender, parser, result);
        } finally {
            RAW_INPUT.remove();
        }
    }

    @Override
    protected CompletableFuture<Void> executeAsync(CommandContext context) {
        String raw = RAW_INPUT.get();
        List<String> args = TextCommands.split(raw != null ? raw : context.getInputString(), List.of(NAME, ALIAS));
        boolean player = context.isPlayer();
        Actor actor = player ? new Actor(context.sender().getUuid(), context.sender().getUsername()) : Actor.CONSOLE;
        CompletableFuture<List<String>> out;
        try {
            out = text.run(actor, args);
        } catch (Throwable t) {
            logger.atSevere().withCause(t).log("[ModSettingsManager] /%s failed", NAME);
            context.sendMessage(Message.raw("/" + NAME + " failed: " + t.getMessage()));
            return CompletableFuture.completedFuture(null);
        }
        if (out == null || args.isEmpty() && player) {
            // No subcommand: open the window (optionally on the named mod).
            if (!player) {
                TextCommands.usage().forEach(l -> context.sendMessage(Message.raw(l)));
                return CompletableFuture.completedFuture(null);
            }
            String mod = args.isEmpty() ? null : String.join(" ", args);
            if (mod != null && controller.mod(mod).isEmpty()) {
                context.sendMessage(Message.raw("No mod with settings matches \"" + mod + "\". Try /" + NAME + " list."));
                return CompletableFuture.completedFuture(null);
            }
            PlayerRef pr = Universe.get().getPlayer(actor.uuid());
            if (pr == null || !SettingsPage.open(pr, new SettingsWindow(controller, actor, mod), logger)) {
                context.sendMessage(Message.raw("You are not in a world."));
            }
            return CompletableFuture.completedFuture(null);
        }
        return out.handle((lines, err) -> {
            if (err != null) {
                logger.atSevere().withCause(err).log("[ModSettingsManager] /%s failed", NAME);
                context.sendMessage(Message.raw("/" + NAME + " failed: " + err.getMessage()));
            } else {
                for (String l : lines) context.sendMessage(Message.raw(l));
            }
            return null;
        });
    }
}
