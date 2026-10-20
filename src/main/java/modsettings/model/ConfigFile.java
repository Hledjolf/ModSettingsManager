package modsettings.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import modsettings.json.Json;

/**
 * A config file on disk. Reading keeps key order and every key; writing keeps the file's indent, saves
 * the old file as {@code <name>.bak}, writes a temp file next to it and renames it over the original, so
 * a crash mid-write never leaves a half-written config.
 */
public final class ConfigFile {
    private ConfigFile() {}

    /** A file as read: its decoded object and the indent unit it uses. */
    public record Read(Map<String, Object> config, String indent) {}

    public static Read read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return new Read(Json.parseObject(text), Json.detectIndent(text));
    }

    public static Path backupOf(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }

    /** Writes {@code config} to {@code file} safely (see the class comment). */
    public static void write(Path file, Map<String, Object> config, String indent) throws IOException {
        String text = Json.pretty(config, indent == null ? "  " : indent);
        Json.parse(text); // never write something we could not read back
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        if (Files.exists(file)) Files.copy(file, backupOf(file), StandardCopyOption.REPLACE_EXISTING);
        Path tmp = file.resolveSibling(file.getFileName() + ".msm-tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
