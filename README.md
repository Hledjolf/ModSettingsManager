# Mod Settings Manager

An in-game settings window for Hytale servers. It shows every installed mod's `config.json` as checkboxes, number fields, text fields and dropdowns, and saves your changes safely. **It works with mods as they are**: a mod needs no code and no dependency to show up. Mods that want nicer labels, sections and ranges can ship a small optional sidecar file (see [docs/SIDECAR.md](docs/SIDECAR.md)).

Hytale server 0.7.2. MIT licensed.

## Install

1. Put `ModSettingsManager-1.0.1.jar` in the server's `mods` folder. For singleplayer, use `%APPDATA%\Hytale\UserData\Mods\`.
2. Start the server once, so each mod writes its `config.json`.

Nothing else is needed. Mod Settings Manager finds every loaded plugin's config at boot.

## Opening the window

| Command | What it does |
| --- | --- |
| `/modsettings` or `/msm` | Opens the window. |
| `/modsettings <mod>` | Opens it on one mod (its name or `Group:Name`; a unique prefix is enough). |
| `/modsettings list` | Lists the mods with settings, and which have a sidecar, live apply or a pending restart. |
| `/modsettings show <mod>` | Prints a mod's settings with their current values. |
| `/modsettings set <mod> <key> <value>` | Changes one setting, the same way Apply does. Works from the server console. |
| `/modsettings reload` | Looks for mods, configs and sidecars again (for example after adding a sidecar). |

`/settings` is left alone, since another mod uses it.

## The window

- **Left:** the mods with a config file, with a search box. The search matches mod names and the names and labels of their settings, so you can search for a setting without knowing which mod has it.
- **Right:** the selected mod's settings, grouped into sections when the mod has a sidecar. Hover over a label for its description, its key in the file and its default.
- **A gold `*`** marks each value you changed and haven't applied yet. The status line counts them.
- **Apply** checks every value (type, range, allowed options) and saves. If anything is invalid, nothing is saved and the status line says why.
- **Revert** throws away unapplied changes and shows the file's values again.
- **Reset to default** fills in each setting's default from the mod's sidecar. Review the marked values, then Apply or Revert.
- **The restart banner** lists the saved changes that take effect only after a restart. A mod with no sidecar and no live-apply hook gets this for every change, which is the safe assumption.
- **The warning banner** appears after a restart if a mod put a value back (see below).

### Controls

| Value in the file | Control |
| --- | --- |
| `true` / `false` | Checkbox |
| Whole number | Number field (no decimals) with `-` and `+` buttons |
| Number with a fraction | Number field with `-` and `+` buttons |
| Text | Text field |
| Text with options from the sidecar | Dropdown |
| List, nested object, `null` | Read-only display: edit the file to change it |

When a sidecar gives a range (`min`, `max`), it appears next to the field and Apply enforces it.

**The `-` and `+` buttons** step a number up or down:

- **Step size:** the sidecar's `step`. Without one, whole numbers step by 1, and numbers with a fraction step by one unit of the default's last decimal (a default of `0.5` steps by 0.1, `0.25` by 0.01). With no default, the current value's decimals are used the same way.
- **Range:** a step never leaves `min`/`max`; it stops at the end. The button that would leave the range is disabled.
- **One step per click.** Hytale's raw pages have no press-and-hold event, so holding a button doesn't repeat.
- **A step is an edit like typing.** The row gets its `*`, and nothing is saved until Apply.
- **Clicking fast:** each click steps from the value in the field, and the server then sets the field to the result. A click that arrives while the page is still updating is dropped by Hytale, and then neither the field nor the saved draft changes, so the two always agree. In practice the server takes one step per page update, so very fast clicking can skip clicks.

**No sliders in 1.0.** Hytale's raw `.ui` pages have a slider control, but no vanilla page sets its range from the server, so whether that works can't be checked without a client. Numbers use number fields with steppers instead.

Nested objects show up as one read-only row, unless the mod's sidecar names a key inside them (such as `Seeder.CellSize`). Then each value inside that object gets its own control.

## Who can change settings

Anyone can open the window and look. Applying a change needs one of:

- operator status (`/op add <player>`; in singleplayer the world's owner is an operator);
- the permission `modsettings.edit` (for example `/perm user add <uuid> modsettings.edit`).

Players without either see the window with Apply disabled. The server checks again on every Apply, and a refused attempt is logged.

## How changes are saved

1. Every value is checked first.
2. The file is read again, so edits made by hand in the meantime are kept, and only the keys you changed are written.
3. The old file is kept as `config.json.msm-bak` (not `.bak`, which the server's own config codec uses).
4. The new file is written to a temporary file next to it, which is then renamed over the original, so a crash can't leave a half-written config.
5. Key order, keys Mod Settings Manager doesn't know, the file's indentation and every value are kept. The layout of lists and objects may be normalized: short lists of plain values are written on one line, longer ones one item per line, and a hand-written one-line object is expanded.
6. Each change is added to `mods/ModSettingsManager_ModSettingsManager/changes.log`, one line per key: time, player, mod, file, key, old value, new value.

Then, if the mod has the live-apply hook, Mod Settings Manager tells it what changed (on the server's main world thread). The mod says whether the change took effect live. If not, or if the mod has no hook, the change waits for a restart, and the window says so.

If the mod's hook throws, it has rejected the value. The value stays saved, but a restart would not apply it, so the window says "Saved, but <mod> could not apply: <settings>. See the server log." instead of "restart needed".

### Typing values in chat or the console

`/modsettings set <mod> <key> <value>` goes through Hytale's command parser first:

- A backslash is an escape character, so type each one twice: `D:\\Games\\notes` arrives as `D:\Games\notes`. A single backslash is refused by the parser.
- The parser puts spaces around `[` and `]`, so `[{player}]` is stored as `[ {player} ]`.
- Mod Settings Manager splits the arguments on spaces, so a run of several spaces becomes one.
- Commas and square brackets can't be typed in a `set` value at all: Hytale's tokenizer splits them off as separate tokens (a comma comes back as ` , `), and `[]` is refused by the parser before Mod Settings Manager sees it. A value such as `{day} of {monthName}, {year}` can't be set from chat or the console.

The settings window has none of these limits. Use it for paths, templates with commas or square brackets, or text with repeated spaces.

### After a restart: "the mod put a value back"

Some mods save their in-memory settings when the server stops. That overwrites any edit made while the server was running. Mod Settings Manager remembers what it wrote (in `written.json`). After the next restart it checks each file. If a value went back, it logs a warning, shows a warning banner on that mod, and tags the mod `[put back]` in `/modsettings list` (details in `/modsettings show`). For such a mod, change the setting while the server is stopped, or ask its author to add the live-apply hook.

## Configuration

`mods/ModSettingsManager_ModSettingsManager/config.json`. These are editable in the window too, since Mod Settings Manager lists itself.

| Key | Default | Meaning |
| --- | --- | --- |
| `AnnounceChanges` | `false` | When someone applies a change, every online player who may edit settings gets a chat line saying who changed what. Applies live. |
| `HiddenPlugins` | `[]` | Plugin ids (`Group:Name`) to leave out of the window. Edit the file, then run `/modsettings reload`. |

## Files

| File | What |
| --- | --- |
| `mods/<Group>_<Name>/config.json` | Each mod's config, as the mod wrote it. Mod Settings Manager edits it in place. |
| `mods/<Group>_<Name>/config.json.msm-bak` | The config as it was before the last change. |
| `mods/ModSettingsManager_ModSettingsManager/changes.log` | Every change, one line per key. |
| `mods/ModSettingsManager_ModSettingsManager/written.json` | Values written since the last restart, for the put-back check. |

## Troubleshooting

- **A mod is missing from the list.** It has no `config.json` in its data folder yet. Start the server once, then run `/modsettings reload`. Mods that keep settings somewhere else can point to their file with a sidecar.
- **A click did nothing.** Hytale drops a click that arrives while the page is still updating. Click again.
- **"Its sidecar was rejected".** The mod's sidecar has an error. The server log has a line starting `[ModSettingsManager] Rejected sidecar` with the reason. The mod's settings still show, with inferred controls.

## For mod authors

Your mod already works with Mod Settings Manager if it keeps a `config.json` in its data folder. To add labels, descriptions, sections, ranges, dropdowns and defaults, ship a sidecar file. To apply changes without a restart, add one public method. Neither needs a dependency, and both are ignored when Mod Settings Manager isn't installed. See [docs/SIDECAR.md](docs/SIDECAR.md) and the schema in [schema/settings-sidecar.schema.json](schema/settings-sidecar.schema.json).

## Building from source

Source: https://github.com/Hledjolf/ModSettingsManager. Report problems at https://github.com/Hledjolf/ModSettingsManager/issues.

Java 25 JDK. The build compiles against your local `HytaleServer.jar`, which is never bundled. Set its path as `hytaleServerJar=<path>` in `~/.gradle/gradle.properties`; the default is the launcher install under `%APPDATA%`.

```
./gradlew check                 # the adapter-boundary check and the jar-contents check
./gradlew release               # build/release/ModSettingsManager-1.0.1.jar and .zip (runs check first)
```

Every Hytale API call is in the package `modsettings.adapter`, plus the plugin class `modsettings.ModSettingsPlugin`. `checkAdapterBoundary` fails the build otherwise, so a Hytale update breaks only that package. Everything else (sidecar parsing, control inference, the JSON writer, change diffing, the window's layout) is plain Java.

## License

MIT, copyright (c) 2026 Hledjolf. See [LICENSE](LICENSE). The sidecar format and schema are MIT too, so any mod may ship a sidecar freely.
