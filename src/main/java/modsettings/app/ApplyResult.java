package modsettings.app;

import java.util.List;
import java.util.Set;
import modsettings.model.Changes;

/**
 * What happened on Apply.
 *
 * @param errors      validation problems (status INVALID), one per bad value
 * @param restartKeys the applied keys that take effect only after a restart
 * @param failedKeys  the applied keys the mod's hook threw on: saved, but the mod could not apply them
 */
public record ApplyResult(Status status, String message, List<String> errors, List<Changes.Change> changes,
                          Set<String> restartKeys, Set<String> failedKeys, Changes.HookOutcome hook) {
    public enum Status {
        /** The player may not change settings. Nothing was written. */
        REFUSED,
        /** A value failed validation. Nothing was written. */
        INVALID,
        /** No value differs from the file. Nothing was written. */
        NOTHING,
        /** The file was written. */
        WRITTEN,
        /** Reading or writing the file failed. */
        FAILED
    }

    public boolean written() {
        return status == Status.WRITTEN;
    }

    static ApplyResult of(Status s, String message) {
        return new ApplyResult(s, message, List.of(), List.of(), Set.of(), Set.of(), null);
    }
}
