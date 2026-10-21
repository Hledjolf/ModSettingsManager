package modsettings.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import modsettings.json.Json;

/**
 * {@code changes.log}: one tab-separated line per changed key, appended and never rewritten:
 * {@code <UTC time> player=<name> (<uuid>) plugin=<Group:Name> file=<path> key=<key> old=<json> new=<json>}.
 * Values are JSON, so a string reads {@code "text"} and a missing key reads {@code null}.
 */
public final class ChangeLog {
    private final Path file;

    public ChangeLog(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public static String line(Instant time, String player, String plugin, String configFile, Changes.Change ch) {
        return time.truncatedTo(ChronoUnit.SECONDS) + "\tplayer=" + oneLine(player) + "\tplugin=" + plugin + "\tfile=" + configFile
            + "\tkey=" + ch.key() + "\told=" + Json.write(ch.oldValue()) + "\tnew=" + Json.write(ch.newValue());
    }

    public synchronized void append(Instant time, String player, String plugin, String configFile, List<Changes.Change> changes) throws IOException {
        StringBuilder b = new StringBuilder();
        for (Changes.Change ch : changes) b.append(line(time, player, plugin, configFile, ch)).append('\n');
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String oneLine(String s) {
        return s == null ? "?" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
