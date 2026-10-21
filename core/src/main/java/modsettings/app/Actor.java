package modsettings.app;

import java.util.UUID;

/** Who is changing settings: a player, or the server console ({@code uuid} null), which may always edit. */
public record Actor(UUID uuid, String name) {
    public static final Actor CONSOLE = new Actor(null, "console");

    public boolean console() {
        return uuid == null;
    }

    /** As written to changes.log. */
    public String describe() {
        return console() ? "console" : name + " (" + uuid + ")";
    }
}
