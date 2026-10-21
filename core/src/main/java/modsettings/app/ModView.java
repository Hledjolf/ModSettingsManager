package modsettings.app;

import java.util.List;
import java.util.Map;
import modsettings.model.Control;
import modsettings.model.ControlInference;

/**
 * A mod's settings as read from disk just now. {@code error} is set (and the lists are empty) when the config
 * file is missing or not valid JSON.
 */
public record ModView(ModEntry mod, Map<String, Object> config, String indent, List<ControlInference.Group> groups, List<Control> controls, String error) {
    public Control control(String key) {
        for (Control c : controls) if (c.key().equals(key)) return c;
        return null;
    }
}
